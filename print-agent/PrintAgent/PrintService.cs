using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace PrintAgent;

/// <summary>
/// 打印执行。
///
/// 后端下发的永远是已转好的 PDF（服务端用 LibreOffice 统一转换并计算真实页数），
/// 因此助手端不需要再装 Office / 做格式转换，职责只剩下"把 PDF 送进打印机并确认结果"。
///
/// 通道优先级：
///   1) SumatraPDF 静默打印（Windows 首选）：可精确控制份数 / 单双面 / 彩色 / 纸张；
///   2) CUPS 的 lp 命令（Linux / macOS），参数风格与 Windows 对齐；
///   3) Windows Shell 的 print 动词（兜底，参数由默认应用决定，不保证份数与双面）。
///
/// 打印提交后进入 <see cref="SpoolMonitor"/> 回查真实结果。
/// </summary>
public sealed class PrintService
{
    private readonly AgentConfig _cfg;

    public PrintService(AgentConfig cfg) => _cfg = cfg;

    /// <summary>一次打印尝试的结果</summary>
    public sealed record PrintOutcome(
        bool Success,
        string Channel,
        int? Pages,
        int? SpoolJobId,
        string? Error);

    /// <summary>启动自检：确认打印通道与打印机可用，返回警告列表</summary>
    public List<string> HealthCheck()
    {
        var warnings = new List<string>();
        string? sumatra = FindSumatra();
        bool hasLp = FindOnPath("lp") != null;

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            if (sumatra == null)
                warnings.Add("未找到 SumatraPDF，将退化为系统默认打印（份数/双面/彩色可能不生效）。建议安装 SumatraPDF 并配置 SumatraPdfPath。");
        }
        else if (!hasLp)
        {
            warnings.Add("未找到 CUPS 的 lp 命令，无法打印。请安装 cups-client。");
        }

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            string? printer = ResolvePrinterName();
            if (string.IsNullOrWhiteSpace(printer))
                warnings.Add("未检测到系统默认打印机，且未配置 PrinterName。请先在系统中安装并设置默认打印机。");
            else
                Log.Info($"目标打印机：{printer}");

            if (_cfg.VerifyWithSpooler && SpoolMonitor.ListJobs(printer) == null)
                warnings.Add("无法读取打印队列，结果回查将退化为「命令成功即成功」，建议以具备打印权限的账户运行本程序。");
        }
        return warnings;
    }

    /// <summary>执行一次打印</summary>
    public async Task<PrintOutcome> PrintAsync(ApiClient.TaskInfo task, string localFile, CancellationToken ct)
    {
        string ext = Path.GetExtension(localFile).TrimStart('.').ToLowerInvariant();
        if (ext != "pdf")
            throw new NotSupportedException($"后端应下发 PDF，实际收到 .{ext}，请检查服务端转换配置");

        string printer = ResolvePrinterName() ?? "";
        string documentName = Path.GetFileName(localFile);

        // 提交前先记录队列基线，便于在别人的任务里认出自己这一条
        var baseline = SpoolMonitor.ListJobs(string.IsNullOrWhiteSpace(printer) ? null : printer)?
            .Select(j => j.JobId).ToArray() ?? Array.Empty<int>();

        int? jobId;
        string channel;
        if (FindSumatra() is { } sumatra && RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            channel = "SumatraPDF";
            jobId = null;
            await RunSumatraAsync(sumatra, printer, task, localFile, ct);
        }
        else if (FindOnPath("lp") is { } lp)
        {
            channel = "CUPS lp";
            jobId = await RunLpAsync(lp, printer, task, localFile, ct);
        }
        else if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            channel = "Windows Shell";
            jobId = null;
            RunShellPrint(localFile);
        }
        else
        {
            throw new InvalidOperationException(
                "未找到可用的打印通道：Windows 请安装 SumatraPDF，Linux 请安装 cups-client（lp）。");
        }

        if (!_cfg.VerifyWithSpooler)
            return new PrintOutcome(true, channel, task.PageCount, jobId, null);

        var result = await SpoolMonitor.WaitAsync(
            string.IsNullOrWhiteSpace(printer) ? null : printer,
            documentName, baseline, jobId, _cfg, ct);

        switch (result.Outcome)
        {
            case SpoolMonitor.Outcome.Printed:
                return new PrintOutcome(true, channel, result.Pages ?? task.PageCount, jobId, null);

            case SpoolMonitor.Outcome.Failed:
                return new PrintOutcome(false, channel, null, jobId, result.Message);

            default:
                // 队列不可用 / 未观察到任务：按"已提交"处理但记警告，避免因驱动差异误判失败
                Log.Warn($"队列回查未确认（{result.Message}），按已提交处理。通道={channel}");
                return new PrintOutcome(true, channel, task.PageCount, jobId, null);
        }
    }

    private string? ResolvePrinterName()
    {
        if (!string.IsNullOrWhiteSpace(_cfg.PrinterName)) return _cfg.PrinterName;
        return SpoolMonitor.GetDefaultPrinterName();
    }

    // ---------------- SumatraPDF ----------------
    private async Task RunSumatraAsync(string sumatra, string printer, ApiClient.TaskInfo task,
        string file, CancellationToken ct)
    {
        // -print-settings 语法（逗号分隔，顺序不限）：
        //   份数 3x / 单双面 simplex|duplexlong|duplexshort / 彩色 color|monochrome /
        //   纸张 paper=A4 / 缩放 fit|shrink|noscale
        var tokens = new List<string>
        {
            $"{Math.Max(task.Copies, 1)}x",
            task.IsDuplex ? "duplexlong" : "simplex",
            task.IsColor ? "color" : "monochrome",
            $"paper={NormalizePaper(task.PaperSize)}"
        };
        string settings = string.Join(",", tokens);

        var args = new List<string> { "-silent", "-exit-when-done" };
        if (string.IsNullOrWhiteSpace(printer)) args.Add("-print-to-default");
        else
        {
            args.Add("-print-to");
            args.Add(printer);
        }
        args.Add("-print-settings");
        args.Add(settings);
        args.Add(file);

        var (code, stdout, stderr) = await RunProcessAsync(sumatra, args,
            TimeSpan.FromSeconds(_cfg.SpoolFinishTimeoutSeconds + 60), ct);
        if (code != 0)
            throw new InvalidOperationException(
                $"SumatraPDF 打印失败(exit={code})：{Trim(stderr.Length > 0 ? stderr : stdout)}");
    }

    private string? FindSumatra()
    {
        if (!string.IsNullOrWhiteSpace(_cfg.SumatraPdfPath) && File.Exists(_cfg.SumatraPdfPath))
            return _cfg.SumatraPdfPath;
        string? onPath = FindOnPath("SumatraPDF");
        if (onPath != null) return onPath;
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return null;

        var candidates = new List<string>
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SumatraPDF", "SumatraPDF.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "SumatraPDF", "SumatraPDF.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                "SumatraPDF", "SumatraPDF.exe"),
        };
        // 便携版常见放法：程序目录下的 tools\SumatraPDF.exe
        candidates.Add(Path.Combine(AppContext.BaseDirectory, "tools", "SumatraPDF.exe"));
        candidates.Add(Path.Combine(AppContext.BaseDirectory, "SumatraPDF.exe"));
        return candidates.FirstOrDefault(File.Exists);
    }

    // ---------------- CUPS lp ----------------
    private async Task<int?> RunLpAsync(string lp, string printer, ApiClient.TaskInfo task,
        string file, CancellationToken ct)
    {
        var args = new List<string>();
        if (!string.IsNullOrWhiteSpace(printer)) { args.Add("-d"); args.Add(printer); }
        args.Add("-n"); args.Add(Math.Max(task.Copies, 1).ToString());
        args.Add("-t"); args.Add(Path.GetFileNameWithoutExtension(file));
        args.Add("-o"); args.Add("media=" + NormalizePaper(task.PaperSize));
        args.Add("-o"); args.Add(task.IsDuplex ? "sides=two-sided-long-edge" : "sides=one-sided");
        args.Add("-o"); args.Add(task.IsColor ? "print-color-mode=color" : "print-color-mode=monochrome");
        args.Add(file);

        var (code, stdout, stderr) = await RunProcessAsync(lp, args, TimeSpan.FromMinutes(2), ct);
        if (code != 0)
            throw new InvalidOperationException($"lp 打印失败(exit={code})：{Trim(stderr.Length > 0 ? stderr : stdout)}");

        // 输出形如：request id is HP-LaserJet-42 (1 file(s))
        var m = System.Text.RegularExpressions.Regex.Match(stdout, @"-(\d+)\s*\(");
        if (m.Success && int.TryParse(m.Groups[1].Value, out int id)) return id;
        return null;
    }

    // ---------------- Windows Shell 兜底 ----------------
    private static void RunShellPrint(string file)
    {
        var psi = new ProcessStartInfo(file)
        {
            Verb = "print",
            UseShellExecute = true,
            CreateNoWindow = true
        };
        using var p = Process.Start(psi)
            ?? throw new InvalidOperationException("无法启动系统打印（默认应用未关联 PDF）。");
        // Shell 打印是异步投递，这里不做等待，结果由队列回查确认
        Log.Warn("使用 Windows Shell 打印动词兜底，份数/双面等参数可能不生效");
    }

    // ---------------- 工具 ----------------
    private static string NormalizePaper(string? paper)
    {
        if (string.IsNullOrWhiteSpace(paper)) return "A4";
        string p = paper.Trim().ToUpperInvariant();
        return p switch
        {
            "A3" or "A4" or "A5" or "LETTER" or "LEGAL" => p,
            _ => "A4"
        };
    }

    private static string? FindOnPath(string exe)
    {
        string pathVar = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string dir in pathVar.Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(dir)) continue;
            foreach (string candidate in new[] { exe, exe + ".exe" })
            {
                try
                {
                    string full = Path.Combine(dir.Trim(), candidate);
                    if (File.Exists(full)) return full;
                }
                catch { /* 非法路径字符，跳过 */ }
            }
        }
        return null;
    }

    private static string Trim(string s)
    {
        s = s.Trim();
        return s.Length <= 400 ? s : s[..400] + "…";
    }

    private static async Task<(int Code, string StdOut, string StdErr)> RunProcessAsync(
        string fileName, IEnumerable<string> args, TimeSpan timeout, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(fileName)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        using var p = Process.Start(psi) ?? throw new InvalidOperationException($"无法启动进程：{fileName}");
        var stdOut = p.StandardOutput.ReadToEndAsync(CancellationToken.None);
        var stdErr = p.StandardError.ReadToEndAsync(CancellationToken.None);

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);
        try
        {
            await p.WaitForExitAsync(timeoutCts.Token);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(entireProcessTree: true); } catch { /* ignore */ }
            if (ct.IsCancellationRequested) throw;
            throw new TimeoutException($"打印进程超时（{timeout.TotalSeconds:F0}s）：{Path.GetFileName(fileName)}");
        }
        return (p.ExitCode, await stdOut, await stdErr);
    }
}

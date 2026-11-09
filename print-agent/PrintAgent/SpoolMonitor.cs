using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace PrintAgent;

/// <summary>
/// 打印结果回查：不看"命令是否执行成功"，而看"任务是否真的从打印队列出去了"。
///
/// Windows：Win32 Spooler（OpenPrinter / EnumJobs JOB_INFO_2），
///   能拿到队列内任务的文档名、状态位、总页数、已打印页数；
///   任务出现 → 状态正常 → 从队列消失 = 真正打印完成。
///   JOB_STATUS_ERROR / PAPEROUT / OFFLINE / USER_INTERVENTION / BLOCKED_DEVQ 等状态
///   持续超过宽限期则判定失败（缺纸、卡纸、脱机、驱动报错都能抓到）。
/// Linux：CUPS（lpstat -W not-completed -o），任务从"未完成"列表消失 = 完成。
///
/// 该模块任何异常都不应导致打印流程中断：监控不可用时返回 NotObserved，
/// 由调用方按 VerifyWithSpooler 配置决定是放行还是判失败。
/// </summary>
public static class SpoolMonitor
{
    public sealed record JobSnapshot(
        int JobId,
        string Document,
        uint Status,
        int TotalPages,
        int PagesPrinted,
        int Position)
    {
        public bool HasError => (Status & (JobStatus.Error | JobStatus.PaperOut | JobStatus.Offline |
                                          JobStatus.UserIntervention | JobStatus.BlockedDevQ)) != 0;
        public bool IsPausedOrDeleted => (Status & (JobStatus.Paused | JobStatus.Deleted)) != 0;
        public bool IsPrinted => (Status & JobStatus.Printed) != 0;

        public string Describe()
        {
            var flags = new List<string>();
            if ((Status & JobStatus.Printing) != 0) flags.Add("打印中");
            if ((Status & JobStatus.Paused) != 0) flags.Add("已暂停");
            if ((Status & JobStatus.Error) != 0) flags.Add("驱动报错");
            if ((Status & JobStatus.PaperOut) != 0) flags.Add("缺纸");
            if ((Status & JobStatus.Offline) != 0) flags.Add("打印机脱机");
            if ((Status & JobStatus.UserIntervention) != 0) flags.Add("需要人工处理");
            if ((Status & JobStatus.BlockedDevQ) != 0) flags.Add("队列被阻塞");
            if ((Status & JobStatus.Deleted) != 0) flags.Add("被删除");
            if ((Status & JobStatus.Printed) != 0) flags.Add("已打印");
            if (flags.Count == 0) flags.Add("排队中");
            return string.Join("/", flags);
        }
    }

    public enum Outcome
    {
        /// <summary>队列确认已打印完成</summary>
        Printed,

        /// <summary>队列报错（缺纸/脱机/卡纸/被删除）</summary>
        Failed,

        /// <summary>监控不可用（非 Windows/Linux、权限不足等），无法确认</summary>
        NotObserved,

        /// <summary>监控可用但始终没看到任务，或等待超时</summary>
        Timeout
    }

    public sealed record Result(Outcome Outcome, int? Pages, string Message)
    {
        public static Result Printed(int? pages) => new(Outcome.Printed, pages, "队列确认已打印完成");
        public static Result Failed(string msg) => new(Outcome.Failed, null, msg);
        public static Result NotObserved(string msg) => new(Outcome.NotObserved, null, msg);
        public static Result Timeout(string msg) => new(Outcome.Timeout, null, msg);
    }

    // ---------------- 对外入口 ----------------

    /// <summary>列出指定打印机的队列任务；监控不可用时返回 null</summary>
    public static IReadOnlyList<JobSnapshot>? ListJobs(string? printer)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return ListJobsWindows(printer);
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux)) return ListJobsCups(printer);
        return null;
    }

    public static string? GetDefaultPrinterName()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows)) return null;
        try
        {
            uint size = 0;
            GetDefaultPrinter(null, ref size);
            if (size == 0) return null;
            var sb = new System.Text.StringBuilder((int)size);
            return GetDefaultPrinter(sb, ref size) ? sb.ToString() : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 等待一条打印任务走完队列。
    /// </summary>
    /// <param name="printer">打印机名（null = 默认打印机）</param>
    /// <param name="documentName">提交时使用的文档名（用于在队列中定位自己的任务）</param>
    /// <param name="baselineJobIds">提交前已存在的任务号，用于排除别人的任务</param>
    /// <param name="hintJobId">已知任务号（如 lp 返回的 request id）</param>
    public static async Task<Result> WaitAsync(
        string? printer,
        string documentName,
        IReadOnlyCollection<int> baselineJobIds,
        int? hintJobId,
        AgentConfig cfg,
        CancellationToken ct)
    {
        var probe = ListJobs(printer);
        if (probe == null)
            return Result.NotObserved("当前平台不支持打印队列查询");

        DateTime start = DateTime.UtcNow;
        DateTime? errorSince = null;
        DateTime? appeared = null;
        int? jobId = hintJobId;
        JobSnapshot? last = null;
        int idlePolls = 0;

        while (!ct.IsCancellationRequested)
        {
            double elapsed = (DateTime.UtcNow - start).TotalSeconds;
            var jobs = ListJobs(printer);
            if (jobs == null) return Result.NotObserved("打印队列查询失败");

            JobSnapshot? mine = jobId != null
                ? jobs.FirstOrDefault(j => j.JobId == jobId)
                : MatchJob(jobs, documentName, baselineJobIds);

            if (mine == null)
            {
                if (jobId != null)
                {
                    // 曾经在队列里，现在消失了 → 正常出队（打印完成）或被删除
                    if (errorSince != null)
                        return Result.Failed($"打印任务异常退出队列：{last?.Describe() ?? "未知状态"}");
                    int? pages = last?.PagesPrinted > 0 ? last.PagesPrinted
                        : last is { TotalPages: > 0 } ? last.TotalPages : null;
                    return Result.Printed(pages);
                }
                if (elapsed > cfg.SpoolAppearTimeoutSeconds)
                    return Result.Timeout($"{cfg.SpoolAppearTimeoutSeconds}s 内未在打印队列中发现该任务");
            }
            else
            {
                jobId ??= mine.JobId;
                last = mine;
                appeared ??= DateTime.UtcNow;

                if (mine.IsPausedOrDeleted)
                    return Result.Failed($"打印任务被中止：{mine.Describe()}");

                if (mine.HasError)
                {
                    errorSince ??= DateTime.UtcNow;
                    double badFor = (DateTime.UtcNow - errorSince.Value).TotalSeconds;
                    Log.Warn($"任务 #{mine.JobId} 状态异常（{mine.Describe()}），已持续 {badFor:F0}s");
                    if (badFor >= cfg.SpoolErrorGraceSeconds)
                        return Result.Failed($"打印机异常：{mine.Describe()}");
                }
                else
                {
                    errorSince = null;
                }

                if (mine.IsPrinted)
                {
                    // 部分驱动打印完成后任务仍在队列里短暂停留，再确认一次就收工
                    if (++idlePolls >= 2)
                        return Result.Printed(mine.PagesPrinted > 0 ? mine.PagesPrinted : mine.TotalPages);
                }
                else
                {
                    idlePolls = 0;
                }
            }

            if (appeared != null && elapsed > cfg.SpoolFinishTimeoutSeconds)
                return Result.Timeout($"等待打印完成超时（{cfg.SpoolFinishTimeoutSeconds}s），任务仍停留在队列");

            try
            {
                await Task.Delay(800, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
        return Result.Timeout("等待打印结果被取消");
    }

    /// <summary>在队列中定位自己的任务：优先按文档名匹配，其次取提交后新增的任务号</summary>
    private static JobSnapshot? MatchJob(IReadOnlyList<JobSnapshot> jobs, string documentName,
        IReadOnlyCollection<int> baseline)
    {
        string doc = Normalize(documentName);
        var byName = jobs.FirstOrDefault(j => Normalize(j.Document) == doc);
        if (byName != null) return byName;
        var byContains = jobs.FirstOrDefault(j => Normalize(j.Document).Contains(doc) && doc.Length > 0);
        if (byContains != null) return byContains;
        return jobs.Where(j => !baseline.Contains(j.JobId))
            .OrderByDescending(j => j.JobId)
            .FirstOrDefault();
    }

    private static string Normalize(string? s)
        => string.IsNullOrEmpty(s) ? "" : Path.GetFileNameWithoutExtension(s).Trim().ToLowerInvariant();

    // ---------------- Windows：Win32 Spooler ----------------

    private const int JobStatusPaused = 0x00000001;
    private const int JobStatusError = 0x00000002;
    private const int JobStatusDeleting = 0x00000004;
    private const int JobStatusSpooling = 0x00000008;
    private const int JobStatusPrinting = 0x00000010;
    private const int JobStatusOffline = 0x00000020;
    private const int JobStatusPaperOut = 0x00000040;
    private const int JobStatusPrinted = 0x00000080;
    private const int JobStatusDeleted = 0x00000100;
    private const int JobStatusBlockedDevQ = 0x00000200;
    private const int JobStatusUserIntervention = 0x00400000;

    private static class JobStatus
    {
        public const uint Paused = JobStatusPaused;
        public const uint Error = JobStatusError;
        public const uint Printing = JobStatusPrinting;
        public const uint Offline = JobStatusOffline;
        public const uint PaperOut = JobStatusPaperOut;
        public const uint Printed = JobStatusPrinted;
        public const uint Deleted = JobStatusDeleted;
        public const uint BlockedDevQ = JobStatusBlockedDevQ;
        public const uint UserIntervention = JobStatusUserIntervention;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PrinterDefaults
    {
        public IntPtr pDatatype;
        public IntPtr pDevMode;
        public int DesiredAccess;
    }

    private const int PrinterAccessUse = 0x00000004;

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool OpenPrinter(string? pPrinterName, out IntPtr phPrinter, IntPtr pDefault);

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool OpenPrinterEx(string? pPrinterName, out IntPtr phPrinter, ref PrinterDefaults pDefault);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool ClosePrinter(IntPtr hPrinter);

    [DllImport("winspool.drv", EntryPoint = "EnumJobsW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool EnumJobs(IntPtr hPrinter, uint firstJob, uint numJobs, uint level,
        IntPtr pJob, uint cbBuf, out uint pcbNeeded, out uint pcReturned);

    [DllImport("winspool.drv", EntryPoint = "GetDefaultPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool GetDefaultPrinter(System.Text.StringBuilder? pszBuffer, ref uint pcchBuffer);

    private static IReadOnlyList<JobSnapshot>? ListJobsWindows(string? printer)
    {
        string? name = printer;
        if (string.IsNullOrWhiteSpace(name)) name = GetDefaultPrinterName();

        IntPtr hPrinter = IntPtr.Zero;
        bool opened = !string.IsNullOrWhiteSpace(name) && OpenPrinter(name, out hPrinter, IntPtr.Zero);
        if (!opened)
        {
            // 非管理员权限下 PRINTER_ALL_ACCESS 可能被拒，退化为"使用"权限
            var def = new PrinterDefaults { DesiredAccess = PrinterAccessUse };
            opened = !string.IsNullOrWhiteSpace(name) && OpenPrinterEx(name, out hPrinter, ref def);
        }
        if (!opened)
        {
            Log.Warn($"无法打开打印机 \"{name ?? "(未找到默认打印机)"}\"（错误码 {Marshal.GetLastWin32Error()}），跳过队列回查");
            return null;
        }

        try
        {
            EnumJobs(hPrinter, 0, 255, 2, IntPtr.Zero, 0, out uint needed, out _);
            if (needed == 0) return Array.Empty<JobSnapshot>();

            IntPtr buf = Marshal.AllocHGlobal((int)needed);
            try
            {
                if (!EnumJobs(hPrinter, 0, 255, 2, buf, needed, out needed, out uint returned))
                {
                    Log.Warn($"EnumJobs 失败（错误码 {Marshal.GetLastWin32Error()}），跳过队列回查");
                    return null;
                }
                var list = new List<JobSnapshot>((int)returned);
                for (int i = 0; i < returned; i++)
                {
                    var snap = ReadJobInfo2(buf, i * JobInfo2Size);
                    if (snap != null) list.Add(snap);
                }
                return list;
            }
            finally
            {
                Marshal.FreeHGlobal(buf);
            }
        }
        catch (Exception ex)
        {
            Log.Warn($"读取打印队列异常：{ex.Message}");
            return null;
        }
        finally
        {
            ClosePrinter(hPrinter);
        }
    }

    // ---- JOB_INFO_2W 手工偏移解析（避免结构体封送对齐在 x86/x64 不一致带来的坑） ----
    // 布局：DWORD JobId; 12 × 指针; DWORD Status/Priority/DefaultPriority/StartTime/UntilTime/
    //       TotalPages/Size; SYSTEMTIME(16B); DWORD Time; DWORD PagesPrinted
    private static readonly int PtrSize = IntPtr.Size;
    private static readonly int PtrBase = PtrSize;                 // JobId(4) 按指针宽度对齐
    private static readonly int DwordBase = PtrBase + 12 * PtrSize;
    private static readonly int OffsetDocument = PtrBase + 3 * PtrSize;
    private static readonly int OffsetStatusString = PtrBase + 10 * PtrSize;
    private static readonly int OffsetStatusFlags = DwordBase;
    private static readonly int OffsetPosition = DwordBase + 8;
    private static readonly int OffsetTotalPages = DwordBase + 20;
    private static readonly int OffsetPagesPrinted = DwordBase + 48;
    private static readonly int JobInfo2Size = Align(DwordBase + 52, PtrSize);

    private static int Align(int v, int a) => (v + a - 1) / a * a;

    private static JobSnapshot? ReadJobInfo2(IntPtr basePtr, int offset)
    {
        try
        {
            int jobId = Marshal.ReadInt32(basePtr, offset);
            string document = ReadString(basePtr, offset + OffsetDocument);
            uint status = (uint)Marshal.ReadInt32(basePtr, offset + OffsetStatusFlags);
            int position = Marshal.ReadInt32(basePtr, offset + OffsetPosition);
            int totalPages = Marshal.ReadInt32(basePtr, offset + OffsetTotalPages);
            int pagesPrinted = Marshal.ReadInt32(basePtr, offset + OffsetPagesPrinted);
            return new JobSnapshot(jobId, document, status, totalPages, pagesPrinted, position);
        }
        catch (Exception ex)
        {
            Log.Warn($"解析打印任务信息失败：{ex.Message}");
            return null;
        }
    }

    private static string ReadString(IntPtr basePtr, int offset)
    {
        IntPtr strPtr = Marshal.ReadIntPtr(basePtr, offset);
        return strPtr == IntPtr.Zero ? "" : (Marshal.PtrToStringUni(strPtr) ?? "");
    }

    // ---------------- Linux：CUPS ----------------

    private static readonly Regex LpstatLine = new(@"^(\S+)-(\d+)\s", RegexOptions.Compiled);

    private static IReadOnlyList<JobSnapshot>? ListJobsCups(string? printer)
    {
        try
        {
            var args = new List<string> { "-W", "not-completed", "-o" };
            if (!string.IsNullOrWhiteSpace(printer)) args.Add(printer);
            var (ok, stdout) = RunQuick("lpstat", args);
            if (!ok) return null; // 未安装 cups 客户端 / 服务未运行

            var list = new List<JobSnapshot>();
            foreach (var raw in stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                var m = LpstatLine.Match(raw.Trim());
                if (!m.Success) continue;
                if (!int.TryParse(m.Groups[2].Value, out int id)) continue;
                list.Add(new JobSnapshot(id, m.Groups[1].Value, JobStatusPrinting, 0, 0, 0));
            }
            return list;
        }
        catch
        {
            return null;
        }
    }

    private static (bool Ok, string StdOut) RunQuick(string fileName, IEnumerable<string> args)
    {
        try
        {
            var psi = new ProcessStartInfo(fileName)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi);
            if (p == null) return (false, "");
            string stdout = p.StandardOutput.ReadToEnd();
            if (!p.WaitForExit(5000))
            {
                try { p.Kill(true); } catch { /* ignore */ }
                return (false, "");
            }
            return (p.ExitCode == 0, stdout);
        }
        catch
        {
            return (false, "");
        }
    }
}

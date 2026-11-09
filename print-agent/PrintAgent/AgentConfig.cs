using System.Text.Json;
using System.Text.Json.Serialization;

namespace PrintAgent;

/// <summary>
/// 打印助手配置。优先级：环境变量 &gt; 同目录 agent.json &gt; 默认值。
/// 环境变量：
///   PRINTSYS_API_BASE        后端地址
///   PRINTSYS_AGENT_TOKEN     与后端 printsys.agent.token 一致的共享密钥
///   PRINTSYS_AGENT_ID        助手标识（缺省用机器名）
///   PRINTSYS_TERMINAL_ID     绑定的终端号（需与用户下单终端一致，否则领不到任务）
///   PRINTSYS_PRINTER         目标打印机名称（空 = 系统默认打印机）
///   PRINTSYS_SUMATRA         SumatraPDF 路径
///   PRINTSYS_WORKDIR         工作目录
///   PRINTSYS_POLL_SECONDS    空闲轮询间隔
/// </summary>
public sealed class AgentConfig
{
    /// <summary>后端地址，如 http://192.168.1.10:8080</summary>
    public string ApiBaseUrl { get; set; } = "http://localhost:8080";

    /// <summary>与后端 printsys.agent.token 一致的共享密钥</summary>
    public string AgentToken { get; set; } = "change-me";

    /// <summary>本助手标识（部署点/机器名），用于后端区分多个打印点</summary>
    public string AgentId { get; set; } = Environment.MachineName;

    /// <summary>
    /// 终端号：用户下单时携带的终端标识（页面 ?t=XXX）。
    /// 为空则不按终端过滤，任意任务都会被领取——多打印点部署时务必配置。
    /// </summary>
    public string? TerminalId { get; set; }

    /// <summary>目标打印机名称；为空 = 系统默认打印机</summary>
    public string? PrinterName { get; set; }

    /// <summary>SumatraPDF 可执行文件路径（Windows 推荐）；为空时自动探测</summary>
    public string? SumatraPdfPath { get; set; }

    /// <summary>空闲时的轮询间隔（秒）</summary>
    public int PollSeconds { get; set; } = 3;

    /// <summary>下载与临时文件目录</summary>
    public string WorkDir { get; set; } = Path.Combine(Path.GetTempPath(), "print-agent");

    /// <summary>日志目录（按天滚动，保留 LogRetentionDays 天）</summary>
    public string LogDir { get; set; } = Path.Combine(AppContext.BaseDirectory, "logs");

    public int LogRetentionDays { get; set; } = 14;

    /// <summary>文件下载重试次数（网络抖动容错）</summary>
    public int DownloadRetries { get; set; } = 3;

    /// <summary>
    /// 是否必须由系统打印队列确认任务才判成功。
    /// true（默认，推荐）：提交后监视 spooler / cups 队列，任务出队才回传 SUCCESS，
    ///   出现缺纸/脱机/卡纸/被删除等状态时回传 FAILED —— 即"真实打印结果回查"。
    /// false：只要打印命令退出码为 0 就回传 SUCCESS（部分老式驱动或远程打印服务器下可用）。
    /// </summary>
    public bool VerifyWithSpooler { get; set; } = true;

    /// <summary>等待打印任务出现在队列中的最长时间（秒）</summary>
    public int SpoolAppearTimeoutSeconds { get; set; } = 20;

    /// <summary>等待打印任务完成出队的最长时间（秒）</summary>
    public int SpoolFinishTimeoutSeconds { get; set; } = 180;

    /// <summary>错误状态持续多少秒才判定失败（避免瞬时抖动误判）</summary>
    public int SpoolErrorGraceSeconds { get; set; } = 15;

    /// <summary>打印前校验任务是否已超时；超时则不打印，直接回传失败让后端重排</summary>
    public bool SkipExpiredTask { get; set; } = true;

    [JsonIgnore] public string PrinterDisplay => string.IsNullOrWhiteSpace(PrinterName) ? "系统默认打印机" : PrinterName!;

    [JsonIgnore] public string TerminalDisplay => string.IsNullOrWhiteSpace(TerminalId) ? "（未绑定终端）" : TerminalId!;

    public static AgentConfig Load()
    {
        var cfg = new AgentConfig();
        string jsonPath = Path.Combine(AppContext.BaseDirectory, "agent.json");
        if (File.Exists(jsonPath))
        {
            try
            {
                var fromFile = JsonSerializer.Deserialize<AgentConfig>(File.ReadAllText(jsonPath),
                    new JsonSerializerOptions
                    {
                        PropertyNameCaseInsensitive = true,
                        ReadCommentHandling = JsonCommentHandling.Skip,
                        AllowTrailingCommas = true
                    });
                if (fromFile != null) cfg = fromFile;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[配置] 读取 agent.json 失败，改用默认配置：{ex.Message}");
            }
        }

        cfg.ApiBaseUrl = Env("PRINTSYS_API_BASE", cfg.ApiBaseUrl).TrimEnd('/');
        cfg.AgentToken = Env("PRINTSYS_AGENT_TOKEN", cfg.AgentToken);
        cfg.AgentId = Env("PRINTSYS_AGENT_ID", cfg.AgentId);
        cfg.TerminalId = Nullable(Env("PRINTSYS_TERMINAL_ID", cfg.TerminalId));
        cfg.PrinterName = Nullable(Env("PRINTSYS_PRINTER", cfg.PrinterName));
        cfg.SumatraPdfPath = Nullable(Env("PRINTSYS_SUMATRA", cfg.SumatraPdfPath));
        cfg.WorkDir = Env("PRINTSYS_WORKDIR", cfg.WorkDir);
        if (int.TryParse(Env("PRINTSYS_POLL_SECONDS", cfg.PollSeconds.ToString()), out int poll) && poll > 0)
            cfg.PollSeconds = Math.Min(poll, 60);
        if (string.IsNullOrWhiteSpace(cfg.AgentId)) cfg.AgentId = Environment.MachineName;
        // 配置里写了空串时回落默认目录，避免 Directory.CreateDirectory("") 抛异常
        if (string.IsNullOrWhiteSpace(cfg.WorkDir))
            cfg.WorkDir = Path.Combine(Path.GetTempPath(), "print-agent");
        if (string.IsNullOrWhiteSpace(cfg.LogDir))
            cfg.LogDir = Path.Combine(AppContext.BaseDirectory, "logs");

        Directory.CreateDirectory(cfg.WorkDir);
        return cfg;
    }

    /// <summary>配置体检：返回问题列表，空表示通过</summary>
    public List<string> Validate()
    {
        var problems = new List<string>();
        if (!Uri.TryCreate(ApiBaseUrl, UriKind.Absolute, out var uri) ||
            (uri.Scheme != "http" && uri.Scheme != "https"))
            problems.Add($"ApiBaseUrl 不是合法的 http(s) 地址：{ApiBaseUrl}");
        if (string.IsNullOrWhiteSpace(AgentToken) || AgentToken == "change-me")
            problems.Add("AgentToken 未配置（需与后端 printsys.agent.token 一致）");
        if (PollSeconds < 1) problems.Add("PollSeconds 必须 >= 1");
        if (SpoolFinishTimeoutSeconds <= SpoolAppearTimeoutSeconds)
            problems.Add("SpoolFinishTimeoutSeconds 必须大于 SpoolAppearTimeoutSeconds");
        return problems;
    }

    private static string Env(string name, string? fallback)
        => Environment.GetEnvironmentVariable(name) is { Length: > 0 } v ? v : (fallback ?? "");

    private static string? Nullable(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
}

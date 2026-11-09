namespace PrintAgent;

/// <summary>
/// 极简日志：控制台 + 按天滚动的文件，避免额外依赖。
/// 公共电脑长期无人值守，出问题时要能从 logs/ 里翻到现场。
/// </summary>
public static class Log
{
    private static readonly object Gate = new();
    private static string? _dir;
    private static int _retentionDays = 14;

    public static void Init(string dir, int retentionDays)
    {
        _dir = dir;
        _retentionDays = Math.Max(retentionDays, 1);
        try
        {
            Directory.CreateDirectory(dir);
            Cleanup();
        }
        catch
        {
            _dir = null; // 目录不可写时只写控制台
        }
    }

    public static void Info(string msg) => Write("INFO", msg);
    public static void Warn(string msg) => Write("WARN", msg);
    public static void Error(string msg) => Write("ERROR", msg);
    public static void Error(string msg, Exception ex) => Write("ERROR", $"{msg} :: {ex.GetType().Name}: {ex.Message}");

    private static void Write(string level, string msg)
    {
        string line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] [{level}] {msg}";
        lock (Gate)
        {
            if (level == "ERROR") Console.Error.WriteLine(line);
            else Console.WriteLine(line);

            if (_dir == null) return;
            try
            {
                string file = Path.Combine(_dir, $"agent-{DateTime.Now:yyyyMMdd}.log");
                File.AppendAllText(file, line + Environment.NewLine);
            }
            catch
            {
                // 日志写失败不能影响打印主流程
            }
        }
    }

    private static void Cleanup()
    {
        if (_dir == null) return;
        var limit = DateTime.Now.Date.AddDays(-_retentionDays);
        foreach (var f in Directory.EnumerateFiles(_dir, "agent-*.log"))
        {
            try
            {
                if (File.GetLastWriteTime(f) < limit) File.Delete(f);
            }
            catch { /* 忽略 */ }
        }
    }
}

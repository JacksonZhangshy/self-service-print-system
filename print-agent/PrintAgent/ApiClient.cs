using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace PrintAgent;

/// <summary>
/// 后端 /api/agent/** 客户端。所有接口统一返回 {code,msg,data}，code=0 表示成功。
/// 鉴权：请求头 X-Agent-Token（后端使用常量时间比较，避免时序侧信道）。
/// </summary>
public sealed class ApiClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly AgentConfig _cfg;
    private static readonly JsonSerializerOptions JsonOpts = new() { PropertyNameCaseInsensitive = true };

    public ApiClient(AgentConfig cfg)
    {
        _cfg = cfg;
        _http = new HttpClient
        {
            BaseAddress = new Uri(cfg.ApiBaseUrl),
            // 大文件上传量不大，但下载与状态回传都要稳；单请求 120s 足够
            Timeout = TimeSpan.FromSeconds(120)
        };
        _http.DefaultRequestHeaders.Add("X-Agent-Token", cfg.AgentToken);
        _http.DefaultRequestHeaders.Add("X-Agent-Id", cfg.AgentId);
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("PrintAgent/1.1");
    }

    /// <summary>后端下发的打印任务（对应后端 TaskView）</summary>
    public sealed record TaskInfo(
        long TaskId,
        string OrderNo,
        string FileName,
        long FileId,
        int Copies,
        string ColorMode,
        string Duplex,
        string PaperSize,
        int PageCount,
        string Status,
        string? AgentId,
        string? TerminalId,
        int RetryCount,
        int? ActualPages,
        string? FailReason,
        string? CreatedAt,
        string? ClaimedAt,
        string? DeadlineAt,
        string? FinishedAt)
    {
        /// <summary>后端 deadlineAt 为 LocalDateTime.toString()，解析失败按"未设截止"处理</summary>
        public DateTime? Deadline =>
            DateTime.TryParse(DeadlineAt, out var d) ? d : null;

        public bool IsDuplex => string.Equals(Duplex, "DUPLEX", StringComparison.OrdinalIgnoreCase);
        public bool IsColor => string.Equals(ColorMode, "COLOR", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>接口调用异常（区分业务错误与网络错误，便于决定是否重试）</summary>
    public sealed class AgentApiException : Exception
    {
        public int HttpStatus { get; }
        public bool Transient { get; }

        public AgentApiException(string message, int httpStatus, bool transient) : base(message)
        {
            HttpStatus = httpStatus;
            Transient = transient;
        }
    }

    /// <summary>领一条待打印任务；无任务返回 null</summary>
    public async Task<TaskInfo?> ClaimAsync(CancellationToken ct)
    {
        var body = await PostJsonAsync("/api/agent/tasks/claim",
            new { agentId = _cfg.AgentId, terminalId = _cfg.TerminalId }, ct);
        if (body == null) return null;
        return body.Value.Deserialize<TaskInfo>(JsonOpts);
    }

    /// <summary>
    /// 下载任务文件到指定路径。
    /// 只能下载「自己正在打印的那条任务」，需要传 agentId 做归属校验。
    /// </summary>
    public async Task DownloadTaskFileAsync(long taskId, string destPath, CancellationToken ct)
    {
        Exception? last = null;
        for (int attempt = 1; attempt <= Math.Max(_cfg.DownloadRetries, 1); attempt++)
        {
            try
            {
                await DownloadOnceAsync(taskId, destPath, ct);
                return;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                last = ex;
                if (attempt < _cfg.DownloadRetries)
                {
                    int wait = 500 * (int)Math.Pow(2, attempt - 1);
                    Log.Warn($"下载任务 #{taskId} 第 {attempt} 次失败（{ex.Message}），{wait}ms 后重试");
                    await Task.Delay(wait, ct);
                }
            }
        }
        throw new AgentApiException($"文件下载失败：{last?.Message}", 0, true);
    }

    private async Task DownloadOnceAsync(long taskId, string destPath, CancellationToken ct)
    {
        string url = $"/api/agent/tasks/{taskId}/file?agentId={Uri.EscapeDataString(_cfg.AgentId)}";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        if (!resp.IsSuccessStatusCode)
        {
            string text = await resp.Content.ReadAsStringAsync(ct);
            throw new AgentApiException(ExtractMessage(text, (int)resp.StatusCode),
                (int)resp.StatusCode, IsTransient(resp.StatusCode));
        }
        // 先写临时文件再改名，避免半截文件被送进打印队列
        string tmp = destPath + ".part";
        await using (var fs = File.Create(tmp))
        {
            await resp.Content.CopyToAsync(fs, ct);
        }
        long length = new FileInfo(tmp).Length;
        if (length == 0)
        {
            File.Delete(tmp);
            throw new AgentApiException("下载到的文件为空", 0, true);
        }
        File.Move(tmp, destPath, overwrite: true);
        Log.Info($"已下载任务 #{taskId} 文件 {length / 1024.0:F1} KB → {Path.GetFileName(destPath)}");
    }

    /// <summary>回传打印结果（含实际出纸张数）</summary>
    public async Task ReportAsync(long taskId, string status, string? failReason, int? actualPages,
        CancellationToken ct)
    {
        await PostJsonAsync($"/api/agent/tasks/{taskId}/status", new
        {
            agentId = _cfg.AgentId,
            status,
            failReason = Truncate(failReason, 400),
            actualPages
        }, ct);
    }

    /// <summary>连通性与密钥自检</summary>
    public async Task<bool> PingAsync(CancellationToken ct)
    {
        try
        {
            using var resp = await _http.GetAsync("/api/agent/ping", ct);
            return resp.IsSuccessStatusCode;
        }
        catch
        {
            return false;
        }
    }

    private async Task<JsonElement?> PostJsonAsync(string path, object payload, CancellationToken ct)
    {
        string json = JsonSerializer.Serialize(payload);
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        HttpResponseMessage resp;
        try
        {
            resp = await _http.PostAsync(path, content, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            throw new AgentApiException($"无法连接后端 {_cfg.ApiBaseUrl}：{ex.Message}", 0, true);
        }

        using (resp)
        {
            string text = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode)
                throw new AgentApiException(ExtractMessage(text, (int)resp.StatusCode),
                    (int)resp.StatusCode, IsTransient(resp.StatusCode));

            JsonDocument doc;
            try
            {
                doc = JsonDocument.Parse(text);
            }
            catch (JsonException)
            {
                throw new AgentApiException($"后端返回非 JSON 内容：{Truncate(text, 120)}",
                    (int)resp.StatusCode, false);
            }

            using (doc)
            {
                var root = doc.RootElement;
                if (!root.TryGetProperty("code", out var codeEl) || codeEl.GetInt32() != 0)
                {
                    string msg = root.TryGetProperty("msg", out var msgEl)
                        ? msgEl.GetString() ?? "未知错误" : "未知错误";
                    throw new AgentApiException($"接口 {path} 业务错误：{msg}", (int)resp.StatusCode, false);
                }
                if (!root.TryGetProperty("data", out var data) || data.ValueKind == JsonValueKind.Null)
                    return null;
                return data.Clone();
            }
        }
    }

    private static bool IsTransient(HttpStatusCode code)
        => (int)code == 0 || (int)code >= 500 || code == HttpStatusCode.RequestTimeout
           || code == HttpStatusCode.TooManyRequests;

    private static string ExtractMessage(string body, int status)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("msg", out var m) && m.GetString() is { Length: > 0 } s)
                return s;
        }
        catch { /* 非 JSON，落到通用文案 */ }
        return $"HTTP {status}：{Truncate(body, 160)}";
    }

    private static string Truncate(string? s, int max)
        => string.IsNullOrEmpty(s) ? "" : (s.Length <= max ? s : s[..max] + "…");

    public void Dispose() => _http.Dispose();
}

using PrintAgent;

// ============================================================================
// 自助收费打印系统 · 打印助手（.NET 8 控制台程序）
//
// 工作循环：轮询领取任务 → 下载后端已转好的 PDF → 提交打印 → 回查队列 → 回传结果。
// 部署在连接打印机的公共电脑上（Windows 推荐 SumatraPDF，Linux 用 CUPS）。
//
// 命令行：
//   PrintAgent            正常运行
//   PrintAgent --check    只做环境自检（配置/后端连通/打印通道/打印机）后退出，部署验收用
//   PrintAgent --once     只处理一条任务后退出，联调用
//   PrintAgent --dry-run  领取并下载任务但不真正打印（回传失败原因标记 dry-run），
//                         用于现场联调网络、密钥与终端号，不会浪费纸张
// ============================================================================

Console.OutputEncoding = System.Text.Encoding.UTF8;

var cfg = AgentConfig.Load();
Log.Init(cfg.LogDir, cfg.LogRetentionDays);

bool checkOnly = args.Any(a => a.Equals("--check", StringComparison.OrdinalIgnoreCase));
bool once = args.Any(a => a.Equals("--once", StringComparison.OrdinalIgnoreCase));
bool dryRun = args.Any(a => a.Equals("--dry-run", StringComparison.OrdinalIgnoreCase));

Log.Info("============================================================");
Log.Info("自助收费打印系统 · 打印助手 v1.1");
Log.Info($"后端地址   : {cfg.ApiBaseUrl}");
Log.Info($"助手标识   : {cfg.AgentId}");
Log.Info($"绑定终端   : {cfg.TerminalDisplay}");
Log.Info($"目标打印机 : {cfg.PrinterDisplay}");
Log.Info($"结果回查   : {(cfg.VerifyWithSpooler ? "开启（以打印队列出队为准）" : "关闭（命令成功即成功）")}");
if (dryRun) Log.Warn("dry-run 模式：只领取与下载，不提交打印机");
Log.Info($"轮询间隔   : {cfg.PollSeconds}s    工作目录：{cfg.WorkDir}");
Log.Info($"日志目录   : {cfg.LogDir}");
Log.Info("============================================================");

// ---------------- 配置体检 ----------------
var problems = cfg.Validate();
if (problems.Count > 0)
{
    foreach (var p in problems) Log.Error("配置错误：" + p);
    Log.Error("请修正 agent.json 或环境变量后重新启动。示例见 agent.example.json。");
    return 2;
}

using var api = new ApiClient(cfg);
var printer = new PrintService(cfg);

if (!dryRun)
{
    foreach (var w in printer.HealthCheck()) Log.Warn(w);
}
else
{
    Log.Info($"dry-run：跳过打印通道自检（目标打印机：{cfg.PrinterDisplay}）");
}

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    Log.Info("收到退出信号，正在停止（当前任务会执行完）…");
    cts.Cancel();
};
AppDomain.CurrentDomain.ProcessExit += (_, _) => { try { cts.Cancel(); } catch { /* ignore */ } };

// ---------------- 后端连通性 ----------------
bool online = await api.PingAsync(cts.Token);
if (!online)
    Log.Warn("后端探测失败，将在轮询中持续重试（请检查地址、网络与 AgentToken）");
else
    Log.Info("后端连通正常，AgentToken 校验通过");

if (checkOnly)
{
    Log.Info(online ? "自检完成：后端可达。" : "自检完成：后端不可达，请排查。");
    return online ? 0 : 1;
}

// ---------------- 主循环 ----------------
int consecutiveErrors = 0;
int processed = 0;

while (!cts.Token.IsCancellationRequested)
{
    ApiClient.TaskInfo? task = null;
    string? localFile = null;
    try
    {
        task = await api.ClaimAsync(cts.Token);
        if (task == null)
        {
            consecutiveErrors = 0;
            await DelayAsync(TimeSpan.FromSeconds(cfg.PollSeconds), cts.Token);
            if (once && processed > 0) break;
            if (once) { Log.Info("--once：当前没有待打印任务，退出。"); break; }
            continue;
        }

        consecutiveErrors = 0;
        Log.Info($"领取任务 #{task.TaskId}｜订单 {task.OrderNo}｜{task.FileName}｜" +
                 $"{task.PageCount}页 × {task.Copies}份｜{(task.IsColor ? "彩色" : "黑白")}" +
                 $"{(task.IsDuplex ? "双面" : "单面")}{task.PaperSize}｜重试 {task.RetryCount} 次");

        // 后端已回收的超时任务不再打印，避免"用户已退款却又出纸"
        if (cfg.SkipExpiredTask && task.Deadline is { } deadline && deadline < DateTime.Now)
        {
            Log.Warn($"任务 #{task.TaskId} 已超过处理期限（{deadline:HH:mm:ss}），放弃打印并回传失败");
            await SafeReportAsync(api, task.TaskId, "FAILED", "任务已超时，未执行打印", null, cts.Token);
            continue;
        }

        localFile = Path.Combine(cfg.WorkDir, $"{task.TaskId}_{task.OrderNo}.pdf");
        await api.DownloadTaskFileAsync(task.TaskId, localFile, cts.Token);

        if (dryRun)
        {
            await SafeReportAsync(api, task.TaskId, "FAILED", "dry-run 联调：已领取并下载，未提交打印机", null, cts.Token);
            Log.Info($"dry-run 完成：任务 #{task.TaskId} 已下载 {new FileInfo(localFile).Length / 1024.0:F1} KB，" +
                     "未打印，已回传失败（后端会按策略自动退款）");
            processed++;
            continue;
        }

        var outcome = await printer.PrintAsync(task, localFile, cts.Token);
        if (outcome.Success)
        {
            await SafeReportAsync(api, task.TaskId, "SUCCESS", null, outcome.Pages, cts.Token);
            Log.Info($"任务 #{task.TaskId} 完成｜通道 {outcome.Channel}｜" +
                     $"实打 {outcome.Pages ?? task.PageCount} 页｜队列任务号 {outcome.SpoolJobId?.ToString() ?? "-"}");
            processed++;
        }
        else
        {
            string reason = outcome.Error ?? "打印失败";
            await SafeReportAsync(api, task.TaskId, "FAILED", reason, null, cts.Token);
            Log.Error($"任务 #{task.TaskId} 失败：{reason}");
        }
    }
    catch (OperationCanceledException)
    {
        break;
    }
    catch (ApiClient.AgentApiException ex)
    {
        // 业务错误（任务被回收、不属于本助手等）不需要重试打印，直接进入下一轮
        Log.Error($"领取/回传任务失败：{ex.Message}");
        if (task != null && !ex.Transient)
            await SafeReportAsync(api, task.TaskId, "FAILED", ex.Message, null, cts.Token);
        consecutiveErrors = await BackoffAsync(consecutiveErrors, cts.Token);
    }
    catch (Exception ex)
    {
        Log.Error("处理任务异常", ex);
        if (task != null)
            await SafeReportAsync(api, task.TaskId, "FAILED", ex.Message, null, cts.Token);
        consecutiveErrors = await BackoffAsync(consecutiveErrors, cts.Token);
    }
    finally
    {
        Cleanup(localFile);
    }

    if (once) break;   // --once：处理完一条（或一轮）即退出，联调用
}

Log.Info($"打印助手已退出（本次处理 {processed} 条任务）。");
return 0;

// ---------------- 辅助 ----------------

static void Cleanup(string? localFile)
{
    if (string.IsNullOrEmpty(localFile)) return;
    try
    {
        if (File.Exists(localFile)) File.Delete(localFile);
        string part = localFile + ".part";
        if (File.Exists(part)) File.Delete(part);
    }
    catch (Exception ex)
    {
        Log.Warn($"清理临时文件失败（不影响业务流程）：{ex.Message}");
    }
}

static async Task SafeReportAsync(ApiClient api, long taskId, string status, string? reason,
    int? pages, CancellationToken ct)
{
    for (int attempt = 1; attempt <= 3; attempt++)
    {
        try
        {
            await api.ReportAsync(taskId, status, reason, pages, ct);
            return;
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            Log.Warn($"回传任务 #{taskId} 状态第 {attempt} 次失败：{ex.Message}");
            if (attempt == 3)
            {
                Log.Error($"任务 #{taskId} 状态最终未能回传，后端将在超时后自动回收重排");
                return;
            }
            await DelayAsync(TimeSpan.FromSeconds(attempt * 2), ct);
        }
    }
}

static async Task<int> BackoffAsync(int consecutiveErrors, CancellationToken ct)
{
    int next = Math.Min(consecutiveErrors + 1, 6);
    int seconds = Math.Min(5 * next, 30);
    Log.Warn($"连续异常 {next} 次，{seconds}s 后重试");
    await DelayAsync(TimeSpan.FromSeconds(seconds), ct);
    return next;
}

static async Task DelayAsync(TimeSpan span, CancellationToken ct)
{
    try
    {
        await Task.Delay(span, ct);
    }
    catch (OperationCanceledException)
    {
        // 退出信号，交由外层循环结束
    }
}

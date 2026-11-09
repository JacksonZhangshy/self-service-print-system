# ============================================================
# 自助收费打印系统 · 打印助手安装脚本（Windows 打印点）
#
# 用法：以管理员身份打开 PowerShell，在解包后的目录里执行
#   .\install-agent.ps1 -ApiBaseUrl "http://192.168.1.10:8080" `
#                       -AgentToken "与后端一致的密钥" `
#                       -TerminalId "T01" `
#                       -PrinterName "HP LaserJet Pro M404-M405"
#
# 做的事：
#   1. 检查 .NET 8 运行时、SumatraPDF、目标打印机是否存在
#   2. 生成 agent.json（首次；已存在则备份后覆盖，除非 -KeepConfig）
#   3. 用「计划任务」注册开机自启（无需管理员常驻登录，也不依赖第三方服务包装器）
#   4. 立刻跑一次 --check 自检并显示结果
# ============================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ApiBaseUrl,
    [Parameter(Mandatory = $true)][string]$AgentToken,
    [string]$AgentId = $env:COMPUTERNAME,
    [string]$TerminalId = "",
    [string]$PrinterName = "",
    [string]$SumatraPdfPath = "",
    [switch]$KeepConfig
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $here "PrintAgent.exe"
$cfgPath = Join-Path $here "agent.json"

function Info($m) { Write-Host "[检查] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[警告] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[失败] $m" -ForegroundColor Red; exit 1 }

# ---------- 1. 环境检查 ----------
if (-not (Test-Path $exe)) { Fail "同目录下找不到 PrintAgent.exe，请把本脚本放在发布目录里执行" }

$hasDotnet = $false
try {
    $runtimes = & dotnet --list-runtimes 2>$null
    if ($runtimes -match "Microsoft\.NETCore\.App 8\.") { $hasDotnet = $true }
} catch { }
if (-not $hasDotnet) {
    Warn ".NET 8 运行时未检测到，请先安装：https://dotnet.microsoft.com/download/dotnet/8.0"
    Warn "（若本目录是 self-contained 发布版，可忽略此提示）"
} else { Info ".NET 8 运行时正常" }

if ($PrinterName) {
    $found = Get-Printer -Name $PrinterName -ErrorAction SilentlyContinue
    if (-not $found) {
        $names = (Get-Printer | Select-Object -ExpandProperty Name) -join " / "
        Fail "找不到打印机「$PrinterName」。本机可用打印机：$names"
    }
    Info "打印机存在：$PrinterName"
} else {
    $def = (Get-CimInstance Win32_Printer -Filter "Default=TRUE" -ErrorAction SilentlyContinue).Name
    if ($def) { Info "未指定 PrinterName，将使用系统默认打印机：$def" }
    else { Warn "未指定 PrinterName，且系统没有默认打印机，打印会失败" }
}

if ($SumatraPdfPath) {
    if (Test-Path $SumatraPdfPath) { Info "SumatraPDF：$SumatraPdfPath" }
    else { Fail "SumatraPdfPath 不存在：$SumatraPdfPath" }
} else {
    $candidates = @(
        "$env:LOCALAPPDATA\SumatraPDF\SumatraPDF.exe",
        "$env:ProgramFiles\SumatraPDF\SumatraPDF.exe",
        "${env:ProgramFiles(x86)}\SumatraPDF\SumatraPDF.exe",
        (Join-Path $here "tools\SumatraPDF.exe"),
        (Join-Path $here "SumatraPDF.exe")
    ) | Where-Object { Test-Path $_ }
    if ($candidates) { Info "自动找到 SumatraPDF：$($candidates[0])" }
    else {
        Warn "未找到 SumatraPDF，将退化为系统默认打印（份数/双面可能不生效）"
        Warn "下载：https://www.sumatrapdfreader.org/download-free-pdf-viewer"
    }
}

# ---------- 2. 生成配置 ----------
$cfg = [ordered]@{
    ApiBaseUrl                = $ApiBaseUrl.TrimEnd('/')
    AgentToken                = $AgentToken
    AgentId                   = $AgentId
    TerminalId                = $TerminalId
    PrinterName               = $PrinterName
    SumatraPdfPath            = $SumatraPdfPath
    PollSeconds               = 3
    DownloadRetries           = 3
    VerifyWithSpooler         = $true
    SpoolAppearTimeoutSeconds = 20
    SpoolFinishTimeoutSeconds = 180
    SpoolErrorGraceSeconds    = 15
    SkipExpiredTask           = $true
    LogRetentionDays          = 14
}

if ((Test-Path $cfgPath) -and $KeepConfig) {
    Info "已存在 agent.json，按 -KeepConfig 保留原配置"
} else {
    if (Test-Path $cfgPath) {
        $bak = "$cfgPath.bak-$(Get-Date -Format yyyyMMddHHmmss)"
        Copy-Item $cfgPath $bak
        Info "已备份原配置：$bak"
    }
    ($cfg | ConvertTo-Json -Depth 5) | Set-Content -Path $cfgPath -Encoding UTF8
    Info "已写入 $cfgPath"
}

# ---------- 3. 注册开机自启 ----------
$taskName = "PrintSystemAgent"
$action = New-ScheduledTaskAction -Execute $exe -WorkingDirectory $here
$trigger = New-ScheduledTaskTrigger -AtStartup
# 打印机无人值守：用 SYSTEM 账户运行，不依赖某个用户登录
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Days 0) -MultipleInstances IgnoreNew

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Info "已清理旧的计划任务"
}
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Description "自助收费打印系统 · 打印助手" | Out-Null
Start-ScheduledTask -TaskName $taskName
Info "已注册计划任务「$taskName」并启动（开机自启，SYSTEM 账户运行）"

# ---------- 4. 自检 ----------
Start-Sleep -Seconds 3
Info "运行环境自检…"
& $exe --check
$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host ""
    Write-Host "安装完成。常用命令：" -ForegroundColor Green
    Write-Host "  查看状态 : Get-ScheduledTaskInfo -TaskName $taskName"
    Write-Host "  重启助手 : Restart-ScheduledTask -TaskName $taskName  (或 Stop/Start)"
    Write-Host "  卸载     : Unregister-ScheduledTask -TaskName $taskName -Confirm:`$false"
    Write-Host "  日志     : $here\logs\agent-$(Get-Date -Format yyyyMMdd).log"
} else {
    Warn "自检未通过（退出码 $code），请查看上方输出与日志目录后修正 agent.json，再执行："
    Warn "  Restart-ScheduledTask -TaskName $taskName"
}
exit $code

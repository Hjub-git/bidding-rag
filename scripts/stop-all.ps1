#  一键停止：优雅关闭 Python(8000) + 停 Java(8080) + 停前端(8081)
#
#  用法：
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1

#  路径由 $PSScriptRoot 推导（scripts/ 的上一级就是仓库根），仓库搬到哪都能跑。
#    chromadb 的 HNSW 落盘是非原子的（先 pickle 再四个 bin），强杀正好撞进这个
#    窗口就会留下半截段文件。实测：HNSW 坏 + embeddings_queue 被 compaction
#    裁到 1 → 无法重放 → 服务再也起不来。所以这里先调 POST /api/shutdown，
#    让 Python 侧「校验向量库 → close() → 退出」，等满 20s 才用 Stop-Process 兜底。
#    前端（8081）无落盘状态，可以直接 taskkill /T /F —— 但这条只给前端用。
#
#  退出码：0 = 8081/8000/8080 都已释放且无残留进程；1 = 还有残留（会打印是谁）
[CmdletBinding()]
param(
    [int]$GracefulTimeoutSec = 20
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot          # bidding-rag
$run  = Join-Path $root '_run'
$javaPidFile = Join-Path $run 'java.pid'
$pyPidFile   = Join-Path $run 'py.pid'
$webPidFile  = Join-Path $run 'web.pid'
$pyLog       = Join-Path $run 'py.log'
$script:LogPath = Join-Path $run 'stop-all.log'

# 内部令牌：优先环境变量，其次 start-all.ps1 生成的 _run\rag-token.txt
# （历史默认串已废弃——它是公开的，能被用来直连 8000 绕过权限）
$ragTokenFile = Join-Path $run 'rag-token.txt'
$token = $env:RAG_INTERNAL_TOKEN
if ([string]::IsNullOrWhiteSpace($token) -and (Test-Path -LiteralPath $ragTokenFile)) {
    $token = (Get-Content -LiteralPath $ragTokenFile -Raw).Trim()
}
if ([string]::IsNullOrWhiteSpace($token)) { $token = '' }

New-Item -ItemType Directory -Path $run -Force | Out-Null

function Say([string]$msg) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss.fff'), $msg
    Write-Host $line
    try {
        [System.IO.File]::AppendAllText($script:LogPath, $line + "`r`n",
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}

function Get-PortPid([int]$port) {
    $c = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($c.Count -gt 0) { return [int]$c[0].OwningProcess }
    return 0
}

function Test-Port([int]$port) { return ((Get-PortPid $port) -ne 0) }

Say '================ stop-all 开始 ================'

# ══════════ 1. Python：优雅关闭 ══════════
$pyPid = 0
if (Test-Path $pyPidFile) {
    $txt = (Get-Content $pyPidFile -Raw -ErrorAction SilentlyContinue)
    if ($txt -match '(\d+)') { $pyPid = [int]$Matches[1] }
}
if ($pyPid -eq 0) {
    $pyPid = Get-PortPid 8000
    Say ("  py.pid 缺失/失效，改从 8000 端口 owner 取：pid={0}" -f $pyPid)
} else {
    Say ("[1/5] Python pid={0}（来自 {1}）" -f $pyPid, $pyPidFile)
}

$pyExisted = ($pyPid -ne 0) -and [bool](Get-Process -Id $pyPid -ErrorAction SilentlyContinue)
if (-not $pyExisted) {
    Say '  8000 上没有 Python 进程（可能已停），跳过优雅关闭'
} else {
    Say ("  POST http://127.0.0.1:8000/api/shutdown  (X-Internal-Token: {0}***)" -f
         $token.Substring(0, [Math]::Min(6, $token.Length)))
    $resp = $null; $httpCode = 0; $shutErr = $null
    try {
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8000/api/shutdown' -Method Post `
                -Headers @{ 'X-Internal-Token' = $token } -TimeoutSec 15 -UseBasicParsing -ErrorAction Stop
        $httpCode = [int]$r.StatusCode
        $resp = $r.Content
    } catch {
        $shutErr = $_.Exception.Message
        try { $httpCode = [int]$_.Exception.Response.StatusCode } catch { }
    }
    Say ("  shutdown 响应：http={0} body={1}" -f $httpCode, ($resp -replace '\s+', ' '))
    if ($shutErr) { Say ("  shutdown 调用异常：{0}" -f $shutErr) }

    # 等它自己退（最多 20s）——这是"优雅"的全部意义：给它时间 flush
    $t0 = Get-Date
    $gone = $false
    while (((Get-Date) - $t0).TotalSeconds -lt $GracefulTimeoutSec) {
        if (-not (Get-Process -Id $pyPid -ErrorAction SilentlyContinue)) { $gone = $true; break }
        Start-Sleep -Milliseconds 300
    }
    $waited = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    if ($gone) {
        Say ("  [OK] 进程已优雅退出 pid={0}，等待 {1}s（未使用 Stop-Process）" -f $pyPid, $waited)
    } else {
        Say ("  [WARN] 等满 {0}s 仍未退出，Stop-Process -Force 兜底（可能撞坏 HNSW，重开后务必查 count）" -f $waited)
        Stop-Process -Id $pyPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }

    # 优雅关闭的日志证据：从 py.log 里捞 [SHUTDOWN] 行
    if (Test-Path $pyLog) {
        $hit = @(Get-Content $pyLog -Encoding utf8 -ErrorAction SilentlyContinue |
                 Where-Object { $_ -match '\[SHUTDOWN\]' })
        if ($hit.Count -gt 0) {
            foreach ($h in $hit) { Say ('  日志证据 | ' + $h.Trim()) }
        } else {
            Say '  [WARN] py.log 里没有 [SHUTDOWN] 行（检查是否走到了兜底强杀）'
        }
    }
}

# ══════════ 2. Java：直接停（Spring Boot 无落盘资产，不需优雅）══════════
Say '[2/5] 停止 Java'
$javaPid = 0
if (Test-Path $javaPidFile) {
    $txt = (Get-Content $javaPidFile -Raw -ErrorAction SilentlyContinue)
    if ($txt -match '(\d+)') { $javaPid = [int]$Matches[1] }
}
if ($javaPid -eq 0) { $javaPid = Get-PortPid 8080 }

if (($javaPid -ne 0) -and (Get-Process -Id $javaPid -ErrorAction SilentlyContinue)) {
    Say ("  Stop-Process pid={0}" -f $javaPid)
    Stop-Process -Id $javaPid -Force -ErrorAction SilentlyContinue
} else {
    Say '  8080 上没有 Java 进程（可能已停）'
}

# ══════════ 3. 前端：taskkill /T /F（无状态，强杀安全）══════════
# 注意：taskkill 只给前端用。Python 必须走 /api/shutdown，绝不能进这条路径。
Say '[3/5] 停止前端（8081）'
$webPid = 0
if (Test-Path $webPidFile) {
    $txt = (Get-Content $webPidFile -Raw -ErrorAction SilentlyContinue)
    if ($txt -match '(\d+)') { $webPid = [int]$Matches[1] }
}
if ($webPid -eq 0) {
    $webPid = Get-PortPid 8081
    Say ("  web.pid 缺失/失效，改从 8081 端口 owner 取：pid={0}" -f $webPid)
} else {
    Say ("  web pid={0}（来自 {1}）" -f $webPid, $webPidFile)
}

$killedWeb = @()
if (($webPid -ne 0) -and (Get-Process -Id $webPid -ErrorAction SilentlyContinue)) {
    Say ("  taskkill /PID {0} /T /F" -f $webPid)
    & taskkill.exe /PID $webPid /T /F 2>&1 | ForEach-Object { Say ('  | ' + $_) }
    $killedWeb += $webPid
} else {
    Say '  8081 上没有前端进程（可能已停）'
}

# 兜底：pid 文件过期（例如上一次 serve 挂了换了新 pid）时，按 8081 owner 再收一次
Start-Sleep -Milliseconds 500
$webOwner = Get-PortPid 8081
if ($webOwner -ne 0 -and ($killedWeb -notcontains $webOwner)) {
    Say ("  8081 仍被 pid={0} 监听，taskkill /T /F 兜底" -f $webOwner)
    & taskkill.exe /PID $webOwner /T /F 2>&1 | ForEach-Object { Say ('  | ' + $_) }
    $webPid = $webOwner
    $killedWeb += $webOwner
}

# ══════════ 4. 清残留（启动器外壳 / 端口 owner）══════════
Say '[4/5] 清理残留'
foreach ($port in @(8000, 8080)) {
    $p = Get-PortPid $port
    if ($p -ne 0) {
        $nm = (Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName
        Say ("  {0} 仍被 pid={1}({2}) 监听，强杀" -f $port, $p, $nm)
        Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }
}
# start-all 用的 cmd 外壳（run-py.cmd / run-java.cmd / run-web.cmd）在子进程退出后应自然结束；
# 万一是后台 cmd 挂着，按命令行匹配收掉，避免"看着没进程、端口又被占"。
foreach ($shell in @('run-py.cmd', 'run-java.cmd', 'run-web.cmd')) {
    $outer = @(Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
               Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($shell) })
    foreach ($o in $outer) {
        Say ("  收掉启动器外壳 {0} pid={1}" -f $shell, $o.ProcessId)
        Stop-Process -Id $o.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

# ══════════ 5. 确认端口释放 ══════════
Say '[5/5] 确认端口释放'
Start-Sleep -Seconds 1
$listen8000 = @(Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue)
$listen8080 = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
$listen8081 = @(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue)
Say ("  8081 监听数={0}   8080 监听数={1}   8000 监听数={2}" -f $listen8081.Count, $listen8080.Count, $listen8000.Count)

# 残留判定只认「本服务」的 pid。不能用 Get-Process java,python —— 同机可能有
# 别人（例如并行跑的评测脚本 run_eval.py）的 python/java，那是别人的任务，
# 既不该算我们残留、更不该被我们杀掉。
$ours = @()
if (($pyPid -ne 0) -and (Get-Process -Id $pyPid -ErrorAction SilentlyContinue)) { $ours += "py($pyPid)" }
if (($javaPid -ne 0) -and (Get-Process -Id $javaPid -ErrorAction SilentlyContinue)) { $ours += "java($javaPid)" }
if (($webPid -ne 0) -and (Get-Process -Id $webPid -ErrorAction SilentlyContinue)) { $ours += "web($webPid)" }
Say ("  本服务残留进程：{0}" -f $(if ($ours.Count -eq 0) { '(无)' } else { $ours -join ', ' }))

$others = @(Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ProcessId -ne $pyPid -and $_.ProcessId -ne $javaPid } |
            ForEach-Object { '{0}({1})' -f $_.Name, $_.ProcessId })
if ($others.Count -gt 0) {
    Say ("  环境内另有其它 python/java 进程（非本服务，未动）：{0}" -f ($others -join ', '))
}

Remove-Item $pyPidFile, $javaPidFile, $webPidFile -ErrorAction SilentlyContinue

$ok = ($listen8000.Count -eq 0) -and ($listen8080.Count -eq 0) -and ($listen8081.Count -eq 0) -and ($ours.Count -eq 0)
if ($ok) {
    Say 'RESULT=PASS 8081/8080/8000 已释放'
} else {
    Say 'RESULT=FAIL 仍有端口占用'
}
Say '================ stop-all 结束 ================'

if ($ok) { exit 0 } else { exit 1 }

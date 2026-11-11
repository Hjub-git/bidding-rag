#  一键启动：Java 后端(8080) + Python RAG 服务(8000) + Vue 前端(8081)
#
#  用法（在任意目录；路径全部由 $PSScriptRoot 推导，仓库搬到哪都能跑）：
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
#    （或直接双击 scripts\start-all.bat）
#
#  可选参数：
#    -TimeoutSec 300        等待三端就绪的总上限（秒）；前端另有 150s 子上限
#    -WebTimeoutSec 150     只等前端（vue-cli 首次编译慢）
#    -SkipRepair            跳过 [4/7] 的「向量库检测 / 自愈」（默认会跑：只读检测约 35–45s，
#                           已和前端首次编译并行，实际只多等几秒）
#    -DbUser / -DbPassword  默认取 $env:DB_USER / $env:DB_PASSWORD（密码无内置默认值，缺失会报错）
#
#  启动顺序：[1] 前置 → [2] Java → [3] 前端 → [4] 向量库检测/自愈 → [5] Python → [6] 等就绪 → [7] 就绪
#    前端排在最前是因为它编译最久（~35s），正好把 [4] 的检测等待吃掉；Python 必须等检测出结论
#    （8000 空着 + 库健康）才起，否则坏 HNSW 会让 uvicorn 直接 fail-closed 起不来。
#
#  产物（都在 <仓库根>\_run 下，该目录已进 .gitignore）：
#    java.log  py.log  web.log   三服务的合并输出（启动时先清空，不跨次累积）
#    java.pid  py.pid  web.pid   真实服务进程 pid（从端口 owner 取，不是启动器外壳）
#    start-all.log               本脚本的流程日志（带时间戳）
#    vector-check.log            向量库检测/自愈的原始输出（repair-vector 子进程）
#    jwt-secret.txt / rag-token.txt  自动生成的 JWT / 内部令牌（不要提交）
#
#  停止请用 stop-all.ps1 —— 不要直接 Stop-Process：Python 侧要先调
#  POST /api/shutdown 让 chromadb 收尾，强杀会撞上 HNSW 非原子落盘窗口
#  （实测把段文件写坏过，服务起不来）。前端无状态，允许 taskkill 强杀。
[CmdletBinding()]
param(
    [int]$TimeoutSec = 300,
    [int]$WebTimeoutSec = 150,
    [switch]$SkipRepair,
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'root' }),
    # 不写死密码：默认只读环境变量。缺失时 [1/7] 会明确提示怎么填
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '' })
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# 路径（全部由脚本自身位置推导：scripts/ 的上一级就是仓库根）
$root    = Split-Path -Parent $PSScriptRoot          # bidding-rag
$rag     = Join-Path $root 'rag-service'
$backend = Join-Path $root 'backend'
$webDir  = Join-Path $root 'frontend'
$jar     = Join-Path $backend 'target\bidding-backend-1.0.0.jar'
$run     = Join-Path $root '_run'
$py      = if ($env:RAG_PYTHON) { $env:RAG_PYTHON } else { 'C:\Users\32571\AppData\Local\Programs\Python\Python39\python.exe' }
$javaExe = if ($env:JAVA_EXE) { $env:JAVA_EXE } else { 'C:\Program Files\Java\jdk-17\bin\java.exe' }
$javaLog = Join-Path $run 'java.log'
$pyLog   = Join-Path $run 'py.log'
$webLog  = Join-Path $run 'web.log'
$javaPidFile = Join-Path $run 'java.pid'
$pyPidFile   = Join-Path $run 'py.pid'
$webPidFile  = Join-Path $run 'web.pid'
$script:LogPath = Join-Path $run 'start-all.log'

New-Item -ItemType Directory -Path $run -Force | Out-Null

# 小工具
function Say([string]$msg) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss.fff'), $msg
    Write-Host $line
    try {
        [System.IO.File]::AppendAllText($script:LogPath, $line + "`r`n",
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}

function Fail([string]$msg) {
    Say "[FAIL] $msg"
    if ($script:vdProc -and -not $script:vdProc.HasExited) {
        Say '  （后台向量库检测子进程仍在跑，结果见 _run\vector-check.log；不参与本次失败判定）'
    }
    Say '启动失败，退出码 1。'
    exit 1
}

function Test-Port([int]$port) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(800)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    } catch { return $false }
}

function Get-PortPid([int]$port) {
    $c = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($c.Count -gt 0) { return [int]$c[0].OwningProcess }
    return 0
}

function Test-Health {
    try {
        $r = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/api/health' -TimeoutSec 5 -ErrorAction Stop
        return ($r.status -eq 'ok')
    } catch { return $false }
}

function Test-Login {
    try {
        $r = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/auth/login' -Method Post `
                -ContentType 'application/json' `
                -Body '{"username":"tender01","password":"123456"}' -TimeoutSec 10 -ErrorAction Stop
        return [bool]($r.data.token)
    } catch { return $false }
}

function Test-Web {
    # vue-cli dev server 在编译完成后才 200；编译中连接会被拒（这里就当未就绪）
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8081/' -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        return ([int]$r.StatusCode -eq 200)
    } catch { return $false }
}

function Invoke-VectorRepair([string]$ScriptPath, [string]$PythonExe, [string[]]$ExtraArgs = @()) {
    # repair-vector.ps1 内部有裸 `exit`，只能丢进独立 powershell 子进程跑：
    # dot-source 会把 start-all 自己一起 exit 掉。
    $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath,
             '-Python', $PythonExe) + $ExtraArgs
    $out = (& powershell.exe @all 2>&1 | Out-String)
    return [pscustomobject]@{ code = $LASTEXITCODE; out = $out }
}

function SayFileTail([string]$title, [string]$path, [int]$n = 40) {
    Say ("---- {0} 末 {1} 行 ----" -f $title, $n)
    if (Test-Path $path) {
        Get-Content $path -Tail $n -Encoding utf8 -ErrorAction SilentlyContinue |
            ForEach-Object { Say ('  | ' + $_) }
    } else { Say '  | (文件不存在)' }
}

function Find-ServicePid([int]$port, [int]$shellPid, [string]$exeName, [string]$pattern) {
    # 解析真实服务 pid：端口 owner 最准，但 Get-NetTCPConnection 在高负载下会偶发
    # 返回空（第二次启动就撞上过：health 200 却拿不到 pid）。所以三路兜底：
    #   ① 端口 owner → ② 启动器外壳的直接子进程 → ③ 命令行特征匹配
    $p = Get-PortPid $port
    if ($p -ne 0) { return $p }

    if ($shellPid -ne 0) {
        $c = @(Get-CimInstance Win32_Process -Filter "Name='$exeName'" -ErrorAction SilentlyContinue |
               Where-Object { $_.ParentProcessId -eq $shellPid })
        if ($c.Count -gt 0) { return [int]$c[0].ProcessId }
    }

    $c2 = @(Get-CimInstance Win32_Process -Filter "Name='$exeName'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -match $pattern })
    if ($c2.Count -gt 0) { return [int]$c2[0].ProcessId }
    return 0
}

# 环境变量（子进程继承；显式设一遍，避免上一个 shell 的残留串味）
# JAVA_HOME 由 $javaExe 反推（…\jdk-17\bin\java.exe → …\jdk-17），不再硬编码盘符环境
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:DB_USER = $DbUser
$env:DB_PASSWORD = $DbPassword
# 上传落盘根：必须与 Python 的 Config.DATA_ROOT 同值。
# 这里显式注入绝对路径（<root>\rag-service\data），两端读同一个 RAG_DATA_ROOT，
# 免得 Java 侧相对路径与 Python 侧 BASE_DIR 推导不一致 → /api/ingest 400。
$env:RAG_DATA_ROOT = Join-Path $rag 'data'
New-Item -ItemType Directory -Path $env:RAG_DATA_ROOT -Force | Out-Null

# JWT 密钥：优先环境变量，否则读/生成 _run\jwt-secret.txt
# 仓库里的历史默认 secret 是公开的（可被用来伪造 admin token），已从 application.yml 移除。
# 这里生成 48 字节随机值并持久化，保证多次启动 token 不失效、又不可从源码猜出。
$jwtSecretFile = Join-Path $run 'jwt-secret.txt'
$jwtSecret = $env:JWT_SECRET
if ([string]::IsNullOrWhiteSpace($jwtSecret)) {
    if (Test-Path -LiteralPath $jwtSecretFile) {
        $jwtSecret = (Get-Content -LiteralPath $jwtSecretFile -Raw).Trim()
    } else {
        $bytes = New-Object byte[] 48
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $jwtSecret = [Convert]::ToBase64String($bytes)
        Set-Content -LiteralPath $jwtSecretFile -Value $jwtSecret -NoNewline -Encoding ascii
        Say ("JWT 密钥 = 新生成 48 字节随机值 → {0}" -f $jwtSecretFile)
    }
}
$env:JWT_SECRET = $jwtSecret

# 嵌入模型只从本地缓存加载：离线时不要卡在 HF 下载上（本机模型已就绪）
$env:HF_HUB_OFFLINE = '1'
$env:TRANSFORMERS_OFFLINE = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'

# 内部服务令牌：两侧必须逐字符一致，否则全链路 401。
# 安全：不再使用公开默认值，缺省/旧默认值时生成 32 字节随机令牌并持久化到 _run\rag-token.txt。
$ragTokenFile = Join-Path $run 'rag-token.txt'
$token = $env:RAG_INTERNAL_TOKEN
if ([string]::IsNullOrWhiteSpace($token) -or $token -eq 'rag-internal-dev-token-2026') {
    if (Test-Path -LiteralPath $ragTokenFile) {
        $token = (Get-Content -LiteralPath $ragTokenFile -Raw).Trim()
    } else {
        $tokBytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($tokBytes)
        $token = [Convert]::ToBase64String($tokBytes)
        Set-Content -LiteralPath $ragTokenFile -Value $token -NoNewline -Encoding ascii
        Say ("RAG 内部令牌 = 新生成 32 字节随机值 → {0}" -f $ragTokenFile)
    }
}
$env:RAG_INTERNAL_TOKEN = $token

# 清掉可能串味的开关（v1 / 代理）
foreach ($k in @('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy',
                 'RAG_RETRIEVAL_SOURCE', 'RAG_ALLOW_V1')) {
    Remove-Item "env:$k" -ErrorAction SilentlyContinue
}

Say '================ start-all 开始 ================'
Say ("仓库根   = {0}" -f $root)
Say ("日志     = {0} / {1} / {2}" -f $javaLog, $pyLog, $webLog)
Say ("令牌     = {0}***(len={1})" -f $token.Substring(0, [Math]::Min(6, $token.Length)), $token.Length)

# ══════════ 1. 前置检查 ══════════
Say '[1/7] 前置检查'

if (-not (Test-Path $jar)) { Fail "找不到后端 jar：$jar（先 cd backend 执行 mvn -q clean package -DskipTests）" }
if (-not (Test-Path $py)) { Fail "找不到 Python：$py" }
if (-not (Test-Path $javaExe)) { Fail "找不到 JDK17：$javaExe" }
Say ("  jar      OK  {0:N1} MB  {1:yyyy-MM-dd HH:mm}" -f ((Get-Item $jar).Length / 1MB),
     (Get-Item $jar).LastWriteTime)
Say ("  python   OK  {0}" -f (& $py -V 2>&1))

# 前端前置：node_modules 必须在（不自动 npm install，缺了让人自己决定要不要装）
$webModules = Join-Path $webDir 'node_modules'
if (-not (Test-Path $webDir)) { Fail "找不到前端目录：$webDir" }
if (-not (Test-Path $webModules)) {
    Fail ("找不到前端依赖：{0}`n        先执行：cd `"$webDir`" && npm install（本脚本不自动装依赖）" -f $webModules)
}
# npm 走 cmd 探测：PowerShell 里直接敲 npm 可能命中 npm.ps1，被本机执行策略拦住
$npmVer = (cmd /c "npm -v" 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $npmVer -notmatch '^\d+\.\d+') {
    Fail ("npm 不可用（cmd /c npm -v 输出：{0}）——检查 Node/npm 是否在 PATH，或先在 {1} 执行 npm install" -f $npmVer, $webDir)
}
Say ("  npm      OK  v{0}（{1}）" -f $npmVer, $webDir)
Say ("  node_modules OK  {0}" -f $webModules)
# [4/7] 的前半段：向量库检测现在就在后台跑起来（只读 -DryRun），把它的耗时藏进
#    「端口检查 + 前端首次编译」这段窗口里。它要 35–55s，大头是 repair-vector 内部
#    4~5 次 Get-NetTCPConnection（这台机器每次 6–10s），在外面没法再压。
#    只读口径：repair-vector 只在 %TEMP% 副本上开库，生产库一字节不动；
#    真修复要等 [4/7] 收到 exit=1 才做。本步失败（端口被占 / MySQL 连不上）时它可能还在跑，
#    见 Fail 的提示；repair-vector 自己也会再确认 8000 空闲，不会跟服务抢库。
$vdScript    = if ($env:RAG_REPAIR_SCRIPT) { $env:RAG_REPAIR_SCRIPT } else { Join-Path $PSScriptRoot 'repair-vector.ps1' }
$vdDir       = Join-Path $rag 'vector_data'
$vdReport    = Join-Path $run 'vector-check.log'
$vdRaw       = Join-Path $run 'vector-dryrun.out'
$vdErrRaw    = Join-Path $run 'vector-dryrun.err'
$script:vdProc = $null
$vdMode      = 'run'
$vdStartedAt = Get-Date
if ($SkipRepair)                                 { $vdMode = 'skipped' }
elseif (-not (Test-Path -LiteralPath $vdScript)) { $vdMode = 'no-script' }
elseif (-not (Test-Path -LiteralPath $vdDir))    { $vdMode = 'no-db' }
if ($vdMode -eq 'run') {
    Remove-Item $vdRaw, $vdErrRaw -ErrorAction SilentlyContinue
    try {
        $script:vdProc = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $vdRaw -RedirectStandardError $vdErrRaw `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$vdScript`"", '-DryRun', '-Python', "`"$py`"")
        # 预取句柄：PS 5.1 的 Start-Process -PassThru 对象不预取句柄的话，
        # 进程退出后 .ExitCode 恒为 $null（本机实测踩过）。
        $null = $script:vdProc.Handle
        $vdStartedAt = Get-Date
        Say ("  向量库检测 后台启动 pid={0}（只读 DryRun；[4/7] 收结果，明细 {1}）" -f $script:vdProc.Id, $vdReport)
    } catch {
        Say ("  [WARN] 向量库检测起不来（{0}）：跳过自愈，继续启动" -f $_.Exception.Message)
        $vdMode = 'start-failed'
    }
}

foreach ($port in @(8080, 8000, 8081)) {
    $busy = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($busy.Count -gt 0) {
        $ownerPid = $busy[0].OwningProcess
        $pname = (Get-Process -Id $ownerPid -ErrorAction SilentlyContinue).ProcessName
        Fail ("端口 {0} 已被占用（pid={1} {2}）——先跑 stop-all.ps1 或手工清理" -f $port, $ownerPid, $pname)
    }
}
Say '  端口     8080 / 8000 / 8081 均空闲'

if ([string]::IsNullOrEmpty($DbPassword)) {
    Fail ('没有数据库密码：先设置环境变量 DB_PASSWORD，或传 -DbPassword <密码>' + [Environment]::NewLine +
          '        示例：$env:DB_PASSWORD = ''你的密码'' ; .\scripts\start-all.ps1')
}
if (-not (Test-Port 3306)) { Fail 'MySQL 3306 连不上（先启动 MySQL 服务）' }
# TCP 通了不代表库/账号对：用 Java 同一个驱动口径（pymysql）真连一次
$probe = & $py -c "import pymysql,sys; pymysql.connect(host='127.0.0.1',port=3306,user=r'$DbUser',password=r'$DbPassword',database='bidding_system',charset='utf8mb4').close(); print('DB_OK')" 2>&1
if (($probe | Out-String) -notmatch 'DB_OK') {
    Say ("  MySQL 探测输出：{0}" -f (($probe | Out-String).Trim()))
    Fail 'MySQL 3306 可连但 bidding_system 库/账号连不上（检查 DB_USER / DB_PASSWORD）'
}
Say ("  MySQL    OK  3306 / bidding_system（user={0}）" -f $DbUser)

# ══════════ 2. 起 Java（8080）══════════
Say '[2/7] 启动 Java 后端 :8080'
Remove-Item $javaLog, $javaPidFile -ErrorAction SilentlyContinue
# 用 .cmd 外壳把 stdout+stderr 合并成一份日志（Start-Process 不允许两次重定向到同一文件）。
# .cmd 用 ANSI(GBK) 编码写，cmd.exe 才能正确读含中文的路径。
$javaCmd = Join-Path $run 'run-java.cmd'
$javaBody = "@echo off`r`ncd /d `"$backend`"`r`n`"$javaExe`" -jar `"$jar`" > `"$javaLog`" 2>&1`r`n"
[System.IO.File]::WriteAllText($javaCmd, $javaBody, [System.Text.Encoding]::Default)

$javaShell = Start-Process -FilePath $javaCmd -WindowStyle Hidden -PassThru
Say ("  java 启动器 pid={0}（真实 pid 起来后从 8080 取）" -f $javaShell.Id)

# ══════════ 3. 起前端（8081）══════════
# 前置到向量库检测之前（这个顺序是踩出来的）：npm 首次编译约 35s，正好把检测（约 40s）的等待吃掉；
# 前端与 Python / 向量库无启动依赖，提前起不改任何既有行为。
# npm 必须经 cmd 跑：PowerShell 里直接调 npm 会命中 npm.ps1，被本机执行策略拦住。
# 与 java/py 同款做法：写一个 .cmd 外壳把 stdout+stderr 合并成一份日志。
Say '[3/7] 启动 Vue 前端 :8081（vue-cli-service serve）'
Remove-Item $webLog, $webPidFile -ErrorAction SilentlyContinue
$webCmd = Join-Path $run 'run-web.cmd'
$webBody = "@echo off`r`ncd /d `"$webDir`"`r`ncall npm run serve > `"$webLog`" 2>&1`r`n"
[System.IO.File]::WriteAllText($webCmd, $webBody, [System.Text.Encoding]::Default)

$webShell = Start-Process -FilePath $webCmd -WindowStyle Hidden -PassThru
Say ("  web 启动器 pid={0}（真实 pid 起来后从 8081 取，首次编译较慢）" -f $webShell.Id)

# ══════════ 4. 向量库检测 / 自愈（收后台结果：坏才修，修不动绝不拦启动）══════════
# 检测本体在 [1/7] 就已经后台起跑了（repair-vector -DryRun，只读），这里只等它出结论：
#   退出码 0 = 健康 → 跳过；1 = 检出坏 → 前台跑一次真修复（归档坏库 + 从可重放备份恢复 + 校验）；
#   2（前置不满足）/ 3（缺 chromadb）/ 超时 → 一律只 WARN，继续启动，让 Python 自己 fail-closed，
#   根因留在 py.log 里。绝不让"检测这一步"把启动卡死。
Say '[4/7] 向量库检测 / 自愈（收 [1/7] 后台检测的结果）'
$vdText   = ''
$vdResult = $vdMode
if ($vdMode -eq 'skipped') {
    Say '  -SkipRepair：跳过向量库检测 / 自愈，直接起服务'
} elseif ($vdMode -eq 'no-script') {
    Say ("  [WARN] 找不到 {0}：跳过检测，继续启动（Python 侧会自行判定并 fail-closed）" -f $vdScript)
} elseif ($vdMode -eq 'no-db') {
    Say ("  [WARN] 找不到向量库目录 {0}：跳过检测，继续启动" -f $vdDir)
} elseif ($vdMode -ne 'run') {
    Say '  [WARN] 后台检测没起来：跳过自愈，继续启动'
} else {
    $fin     = $script:vdProc.WaitForExit(180000)
    $vdSecs  = [math]::Round(((Get-Date) - $vdStartedAt).TotalSeconds, 1)
    $vdLines = @()
    if (Test-Path -LiteralPath $vdRaw) {
        try {
            $vdLines = @(([System.IO.File]::ReadAllText($vdRaw,
                [System.Text.Encoding]::GetEncoding(936))) -split "`r?`n")
        } catch { }
    }
    $vdText += ("===== DryRun {0}s =====" -f $vdSecs) + "`r`n" + ($vdLines -join "`r`n") + "`r`n"

    if (-not $fin) {
        Say ("  [WARN] 后台检测 180s 仍未结束：跳过自愈，继续启动（只读进程，结果见 {0}）" -f $vdReport)
        $vdResult = 'timeout'
    } else {
        $vdCode  = $script:vdProc.ExitCode
        if ($null -eq $vdCode) {
            # 兜底：repair-vector 每条退出路径都会打 "RESULT=... exit=N"，句柄拿不到时按输出取
            $hitc = @($vdLines | Where-Object { $_ -match 'RESULT=\S+ exit=(\d+)' } | Select-Object -Last 1)
            if ($hitc.Count -gt 0 -and $hitc[0] -match 'exit=(\d+)') { $vdCode = [int]$Matches[1] }
        }
        $vdCount = ''
        $hit = @($vdLines | Where-Object { $_ -match 'OK count=(\d+)' } | Select-Object -Last 1)
        if ($hit.Count -gt 0 -and $hit[0] -match 'OK count=(\d+)') { $vdCount = $Matches[1] }

        if ($vdCode -eq 0) {
            Say ("  [向量库] OK count={0}（检测耗时 {1}s，已与前置检查 / 前端编译并行）" -f $vdCount, $vdSecs)
            $vdResult = 'ok'
        } elseif ($vdCode -eq 1) {
            Say ("  [向量库] BAD（检测耗时 {0}s）：判定不健康，开始自动修复" -f $vdSecs)
            foreach ($l in @($vdLines | Where-Object { $_ -match '\]\s+- ' } | Select-Object -First 4)) {
                Say ('    ' + $l.Trim())
            }
            $fix      = Invoke-VectorRepair $vdScript $py
            $fixLines = @($fix.out -split "`r?`n")
            $vdText  += ("`r`n===== repair exit={0} =====" -f $fix.code) + "`r`n" + $fix.out + "`r`n"
            $fixCount = ''
            $hitf = @($fixLines | Where-Object { $_ -match 'REPAIRED count=(\d+)' } | Select-Object -Last 1)
            if ($hitf.Count -gt 0 -and $hitf[0] -match 'REPAIRED count=(\d+)') { $fixCount = $Matches[1] }
            if ($fix.code -eq 0) {
                Say ("  [向量库] 已自动修复 count={0}（repair-vector exit=0），继续启动" -f $fixCount)
                $vdResult = 'repaired'
            } else {
                Say ("  [WARN] 自动修复失败（repair-vector exit={0}，多为无可用备份）：继续启动，让 Python 侧自行 fail-closed" -f $fix.code)
                foreach ($l in @($fixLines | Where-Object { $_ -match '\[(FAIL|WARN)\]' } | Select-Object -Last 4)) {
                    Say ('    ' + $l.Trim())
                }
                Say ('        处置：停服务后手工跑 {0}（必要时 -Backup <名字>），或重新 ingest' -f (Join-Path $PSScriptRoot 'repair-vector.ps1'))
                $vdResult = 'repair-failed'
            }
        } elseif ($vdCode -eq 2) {
            Say ("  [WARN] 检测前置不满足（exit=2）：跳过自愈，继续启动（{0}s）" -f $vdSecs)
            foreach ($l in @($vdLines | Where-Object { $_ -match '\[(FAIL|WARN)\]' } | Select-Object -Last 2)) {
                Say ('    ' + $l.Trim())
            }
            $vdResult = 'skip-precondition'
        } elseif ($vdCode -eq 3) {
            Say ("  [WARN] 环境缺 chromadb / 探针无输出（exit=3）：跳过自愈，继续启动（{0}s）" -f $vdSecs)
            $vdResult = 'skip-no-chromadb'
        } else {
            Say ("  [WARN] 检测返回未知退出码 [{0}]：跳过自愈，继续启动（明细见 {1}）" -f $vdCode, $vdReport)
            $vdResult = 'unknown-exit'
        }
    }
}
try {
    [System.IO.File]::WriteAllText($vdReport, ("[向量库] result={0}`r`n" -f $vdResult) + $vdText,
        (New-Object System.Text.UTF8Encoding($false)))
} catch { }
# ══════════ 5. 起 Python（8000）══════════
Say '[5/7] 启动 Python RAG 服务 :8000'
Remove-Item $pyLog, $pyPidFile -ErrorAction SilentlyContinue
$pyCmd = Join-Path $run 'run-py.cmd'
$pyBody = "@echo off`r`ncd /d `"$rag`"`r`n`"$py`" -X utf8 -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --log-level info > `"$pyLog`" 2>&1`r`n"
[System.IO.File]::WriteAllText($pyCmd, $pyBody, [System.Text.Encoding]::Default)

$pyShell = Start-Process -FilePath $pyCmd -WindowStyle Hidden -PassThru
Say ("  python 启动器 pid={0}（真实 pid 起来后从 8000 取）" -f $pyShell.Id)

# ══════════ 6. 轮询就绪（≤ TimeoutSec，前端另有 WebTimeoutSec 子上限）══════════
Say ("[6/7] 等待三端就绪（health 200 + login 200 + 前端 200，总上限 {0}s / 前端 {1}s）" -f $TimeoutSec, $WebTimeoutSec)
$t0 = Get-Date
$webT0 = $t0
$healthOk = $false; $loginOk = $false; $webOk = $false
$pyPid = 0; $javaPid = 0; $webPid = 0
while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
    if ($pyPid -eq 0) {
        $p = Find-ServicePid 8000 $pyShell.Id 'python.exe' 'uvicorn\s+app\.main:app'
        if ($p -ne 0) {
            $pyPid = $p
            Set-Content -Path $pyPidFile -Value $p -Encoding ascii
            Say ("  python 就绪 pid={0}  ({1:N1}s) → {2}" -f $p, ((Get-Date) - $t0).TotalSeconds, $pyPidFile)
        }
    }
    if ($javaPid -eq 0) {
        $j = Find-ServicePid 8080 $javaShell.Id 'java.exe' 'bidding-backend'
        if ($j -ne 0) {
            $javaPid = $j
            Set-Content -Path $javaPidFile -Value $j -Encoding ascii
            Say ("  java   就绪 pid={0}  ({1:N1}s) → {2}" -f $j, ((Get-Date) - $t0).TotalSeconds, $javaPidFile)
        }
    }
    if (-not $webOk) {
        $webOk = Test-Web
        if ($webOk) {
            Say ("  web    200 OK  ({0:N1}s)" -f ((Get-Date) - $webT0).TotalSeconds)
        } elseif (((Get-Date) - $webT0).TotalSeconds -gt $WebTimeoutSec) {
            Say ("  前端等满 {0}s 仍未 200，放弃" -f $WebTimeoutSec)
            break
        }
    }
    # 前端 pid 只认 8081 端口 owner（与 java.pid 同口径）：
    # npm 这条链是 cmd → node(npm-cli) → node(vue-cli-service)，直接子进程是 npm 外壳，
    # 不是监听者；Find-ServicePid 的子进程兜底在这里会写错 pid（实测写过 npm 外壳）。
    if ($webPid -eq 0) {
        $w = Get-PortPid 8081
        if ($w -ne 0) {
            $webPid = $w
            Set-Content -Path $webPidFile -Value $w -Encoding ascii
            Say ("  web    就绪 pid={0}（8081 owner）  ({1:N1}s) → {2}" -f $w, ((Get-Date) - $t0).TotalSeconds, $webPidFile)
        }
    }
    if (-not $healthOk) { $healthOk = Test-Health }
    if ($healthOk -and -not $loginOk) { $loginOk = Test-Login }

    if ($healthOk -and $loginOk -and $webOk) { break }

    # 早失败：任一侧进程没了就别干等满 TimeoutSec
    if ($pyPid -ne 0 -and -not (Get-Process -Id $pyPid -ErrorAction SilentlyContinue)) {
        Say '  python 进程已退出'
        break
    }
    if ($javaPid -ne 0 -and -not (Get-Process -Id $javaPid -ErrorAction SilentlyContinue)) {
        Say '  java 进程已退出'
        break
    }
    if ($webPid -ne 0 -and -not (Get-Process -Id $webPid -ErrorAction SilentlyContinue)) {
        Say '  web 进程已退出'
        break
    }
    # 前端还没 listen 时没有 owner 可认，只能看启动器外壳是否还活着（编译中就还活着）
    if ($webPid -eq 0 -and -not (Get-Process -Id $webShell.Id -ErrorAction SilentlyContinue)) {
        Say '  web 启动器已退出（前端没起来，看 web.log）'
        break
    }
    Start-Sleep -Milliseconds 1500
}
$elapsed = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)

# 就绪后再补一次 pid：循环可能在「health/login 已通、端口 owner 还没读到」的那一轮
# 就 break 了（实测遇到过一次），不补就会写出 py=0 的 pid 文件，stop 只能靠兜底。
if ($healthOk -and $loginOk) {
    if ($pyPid -eq 0) {
        $p = Find-ServicePid 8000 $pyShell.Id 'python.exe' 'uvicorn\s+app\.main:app'
        if ($p -ne 0) {
            $pyPid = $p
            Set-Content -Path $pyPidFile -Value $p -Encoding ascii
            Say ("  python pid 补记 pid={0}（就绪后补）" -f $p)
        }
    }
    if ($javaPid -eq 0) {
        $j = Find-ServicePid 8080 $javaShell.Id 'java.exe' 'bidding-backend'
        if ($j -ne 0) {
            $javaPid = $j
            Set-Content -Path $javaPidFile -Value $j -Encoding ascii
            Say ("  java pid 补记 pid={0}（就绪后补）" -f $j)
        }
    }
    if ($webPid -eq 0) {
        $w = Get-PortPid 8081
        if ($w -ne 0) {
            $webPid = $w
            Set-Content -Path $webPidFile -Value $w -Encoding ascii
            Say ("  web pid 补记 pid={0}（就绪后补，8081 owner）" -f $w)
        }
    }
}

Say ("  探测结果：health200={0}  login200={1}  web200={2}  py_pid={3}  java_pid={4}  web_pid={5}  耗时={6}s" -f
     $healthOk, $loginOk, $webOk, $pyPid, $javaPid, $webPid, $elapsed)

if (-not ($healthOk -and $loginOk -and $webOk)) {
    SayFileTail 'java.log' $javaLog 40
    SayFileTail 'py.log'   $pyLog   40
    SayFileTail 'web.log'  $webLog  40
    Say '（进程未清，便于排查；确认原因后跑 stop-all.ps1 收尾）'
    Fail '三端未在时限内就绪'
}

# pid 文件是 stop-all 的唯一凭据：拿不到就不算启动成功（否则 stop 只能靠端口兜底）
# 前端再校准一次：web.pid 必须等于 8081 的 owner，否则 stop 会打错进程
$owner8081 = Get-PortPid 8081
if ($owner8081 -ne 0 -and $owner8081 -ne $webPid) {
    Say ("  web pid 校准：{0} → {1}（8081 实际 owner）" -f $webPid, $owner8081)
    $webPid = $owner8081
    Set-Content -Path $webPidFile -Value $webPid -Encoding ascii
}

if ($pyPid -eq 0 -or $javaPid -eq 0 -or $webPid -eq 0) {
    $msg = '服务已就绪但解析不到 pid（py={0} java={1} web={2}），无法保证 stop-all 能安全停止；请手工排查' -f $pyPid, $javaPid, $webPid
    Fail $msg
}

# ══════════ 7. 打印入口 ══════════
Say '[7/7] 就绪'
$line = '=' * 62
Write-Host ''
Write-Host $line -ForegroundColor Green
Write-Host ('  冷启动完成，耗时 {0}s' -f $elapsed) -ForegroundColor Green
Write-Host $line -ForegroundColor Green
Write-Host '  前端    http://localhost:8081      （Vue dev server，本脚本已起，直接打开）'
Write-Host '  后端    http://localhost:8080      （Spring Boot）'
Write-Host '  RAG     http://127.0.0.1:8000/api/health'
Write-Host ''
Write-Host '  演示账号'
Write-Host '    管理员    admin     / admin123'
Write-Host '    招标单位  tender01  / 123456'
Write-Host '    厂家      mfg01     / 123456'
Write-Host ''
Write-Host ('  pid 文件  {0}（py={1}）  {2}（java={3}）  {4}（web={5}）' -f $pyPidFile, $pyPid, $javaPidFile, $javaPid, $webPidFile, $webPid)
Write-Host ('  停止服务  powershell -NoProfile -ExecutionPolicy Bypass -File {0}' -f (Join-Path $PSScriptRoot 'stop-all.ps1'))
Write-Host '            （务必用它；直接 Stop-Process 会撞坏 chromadb 的 HNSW 索引）'
Write-Host $line -ForegroundColor Green
Write-Host ''

Say ("就绪：elapsed={0}s py={1} java={2} web={3}" -f $elapsed, $pyPid, $javaPid, $webPid)
Say '================ start-all 结束 ================'
exit 0

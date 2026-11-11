#  演示数据重建（可选，默认不执行）
#
#  现状：生产库 `rag-service\vector_data` 里已经有 734 个 chunk（tender1 74 + tender2 660），
#  那就是演示数据，正常情况下根本不需要跑这个脚本。
#  只有"换了新机器 / 库被误删"时才用：
#
#      powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\seed-demo.ps1 -Yes
#
#  不带 -Yes 时只打印计划（dry-run），不碰任何数据。
#
#  幂等性（实测）：
#    - Java 侧幂等键 = tender_id + file_name。同一份文件重复上传会复用原 doc_id，
#      入库前先按 doc_id 删旧 chunk 再灌，所以 chunk 总数不会翻倍（734 仍是 734）。
#    - 不同文件名的同一份内容会变成新文档（多一份 chunk），这是预期行为，别拿改名当重传。
#
#   这个脚本会真写向量库。执行前先停服务（stop-all.ps1），跑完再 start-all.ps1，
#    否则正在跑的 Python 进程缓存（doc_chunk_map / BM25）会和磁盘不一致。
[CmdletBinding()]
param(
    [switch]$Yes,
    [string]$User = 'admin',
    [string]$Password = 'admin123'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$fix  = Join-Path $root 'fixtures'
$run  = Join-Path $root '_run'
$java = 'http://127.0.0.1:8080'

# 演示数据 ↔ 项目映射（与库里现有 2 行一致）
$items = @(
    @{ TenderId = 1; File = 'henan_tender.pdf';  Chunks = 74;  Note = 'TP-2026-001 高压电机' },
    @{ TenderId = 2; File = 'hebei_tender.docx'; Chunks = 660; Note = 'TP-2026-002 变压器' }
)

function Test-Port([int]$port) {
    $c = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    return ($c.Count -gt 0)
}

Write-Host '========== seed-demo（演示数据重建）=========='
Write-Host ('目标库：{0}' -f (Join-Path $root 'rag-service\vector_data'))
Write-Host ''

# 前置：服务必须在跑（上传要经 Java 的鉴权 + Python 的入库）
if (-not (Test-Port 8080)) {
    Write-Host '[FAIL] 8080 上没有服务在跑，先执行 start-all.ps1' -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $fix 'henan_tender.pdf'))) {
    Write-Host "[FAIL] 找不到源文件：$fix\henan_tender.pdf" -ForegroundColor Red
    exit 1
}

Write-Host '将执行（会真写向量库）：'
foreach ($it in $items) {
    $p = Join-Path $fix $it.File
    $ok = Test-Path $p
    Write-Host ("  tender_id={0}  {1}  ~{2} chunk   [{3}]  file={4}" -f
                $it.TenderId, $it.Note, $it.Chunks, $(if ($ok) { '存在' } else { '缺失!' }), $it.File)
}
Write-Host ''
Write-Host '注意：这会清掉并重灌这两个 doc_id 的 chunk。' -ForegroundColor Yellow

if (-not $Yes) {
    Write-Host ''
    Write-Host '这是 dry-run：没有做任何修改。' -ForegroundColor Green
    Write-Host '确认要重建，请加 -Yes 再跑一次。' -ForegroundColor Green
    exit 0
}

# 真跑
try {
    $login = Invoke-RestMethod -Uri "$java/api/auth/login" -Method Post `
                -ContentType 'application/json' `
                -Body (@{ username = $User; password = $Password } | ConvertTo-Json -Compress) `
                -TimeoutSec 30 -ErrorAction Stop
    $tok = $login.data.token
    if (-not $tok) { throw '登录没有拿到 token' }
    Write-Host ("登录成功 user={0}" -f $User) -ForegroundColor Green
} catch {
    Write-Host ("[FAIL] 登录失败：{0}（先跑 start-all.ps1）" -f $_.Exception.Message) -ForegroundColor Red
    exit 1
}

$fail = 0
foreach ($it in $items) {
    $p = Join-Path $fix $it.File
    if (-not (Test-Path $p)) { Write-Host "  [SKIP] $($it.File) 不存在"; $fail++; continue }
    Write-Host ("上传 {0} → tender_id={1} ..." -f $it.File, $it.TenderId)
    # 用 curl.exe：PS 5.1 没有 Invoke-RestMethod -Form
    $raw = & curl.exe -s -X POST "$java/api/rag/upload" `
                -H "Authorization: Bearer $tok" `
                -F "file=@$p" -F "tender_id=$($it.TenderId)" 2>&1
    Write-Host ("  <- {0}" -f (($raw | Out-String).Trim() -replace '\s+', ' '))
    if (($raw | Out-String) -match '"code"\s*:\s*200') {
        Write-Host '  OK' -ForegroundColor Green
    } else {
        Write-Host '  非 200，见上面返回体' -ForegroundColor Yellow
        $fail++
    }
}

Write-Host ''
Write-Host '完成后请校验三方一致（只读）：'
Write-Host ('  cd {0}; python t03b_verify.py after_seed    # 期望 CONSISTENT=YES / VEC_COUNT=734' -f $run)
if ($fail -gt 0) { exit 1 } else { exit 0 }

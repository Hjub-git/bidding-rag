# gate.ps1 —— 检索改动的 CI 闸门（Windows / PowerShell）
#
# 三道检查，任一不过即退出码 1：
#   1. matcher 自测（30 条正负例）
#   2. 按「与基线同口径」跑当前配置：golden/seed_80.json + 向量库（默认 eval/vector_base）
#
# 首次运行（向量库不在仓里）
#   eval/vector_base 是本地缓存（已进 .gitignore，不入库）。第一次跑闸门前先建库：
#     python run_eval.py --golden=golden/seed_80.json --out=./runs/bootstrap.json --reingest
#   或者复用已有库：$env:VECTOR_DB_PATH = "..\rag-service\vector_data"（该库需含 tender_id=1 的 74 chunk）
#   基线 baseline_seed80.json 是输入，已随仓库提供（.gitignore 里显式放行），不用自己生成。
#   3. 与基线对比：hit@5 / hit@10 退步超过 3pp 即 FAIL
#
# 用法（路径由 $PSScriptRoot 推导，仓库搬到哪都能跑）：
#   cd .\eval ; .\gate.ps1
#   $env:PYTHON_EXE = "C:\...\Python39\python.exe" ; .\gate.ps1   # 指定解释器（默认 python）
#   .\gate.ps1 -Baseline _t07_baseline_broken.json                # 自证闸门会红
#
# 基线口径（修正过，别再混库）
#   baseline_seed80.json（现役）= 74 chunk + RSF α=0.3 + seed_80(70 可答) → hit@5 72.9 / hit@10 82.9
#   baseline_v2.json（已废弃）  = 58 chunk + RRF      + seed_40(35 可答) → hit@5 77.1 / hit@10 82.9
#
# 为什么必须换：旧闸门拿 seed_40 基线去卡 seed_80 的 run，两套库两套题集，
# 自带约 +17pp（77.1→72.9 一类）的方向性漂移，再叠 19pp 容忍度 → 退步 19pp 才报红，
# 等于没有闸门（74 chunk 的库里 19pp ≈ 13 道题，能整个功能模块挂掉还不报警）。
# 现在 3pp ≈ 2 道题：只放过抽样噪声，不放功能退步。
# 旧 19pp/2pp 的差异不是"更宽松"而是"口径错了"，所以这里同时锁死 baseline_source。

param(
    [string]$Baseline = "baseline_seed80.json"
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

# 3pp：70 题的库里 1 题 = 1.43pp，3pp ≈ 2 题，约等于 Wilson95 半宽的 1/4。
# 小于它的是抽样噪声（同一份代码重跑就会抖），大于它的是真退步。
$MAX_DROP = 3.0

# 允许外部指定解释器；不指定时沿用原来的 `python`，既有行为不变。
$py = if ($env:PYTHON_EXE) { $env:PYTHON_EXE } else { "python" }

$runs = Join-Path $PSScriptRoot "runs"
New-Item -ItemType Directory -Force -Path $runs | Out-Null

Write-Host "=== 1/3 判定器自测 ===" -ForegroundColor Cyan
& $py matcher.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] matcher 自测未通过" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 2/3 跑当前配置（seed_80 + eval/vector_base） ===" -ForegroundColor Cyan
& $py run_eval.py --golden=golden/seed_80.json --out=runs\current.json
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] run_eval 执行失败" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 3/3 与基线对比 ===" -ForegroundColor Cyan
$baseline = if ([System.IO.Path]::IsPathRooted($Baseline)) { $Baseline } else { Join-Path $PSScriptRoot $Baseline }
if (-not (Test-Path $baseline)) {
    Write-Host "[FAIL] 缺基线 $Baseline（先跑 & `$py run_eval.py --golden=golden/seed_80.json --out=baseline_seed80.json）" -ForegroundColor Red
    exit 1
}

# 基线指纹：把「这是哪套库、哪套题集、多少题、多少分」写在闸门输出里，
# 以后有人再拿 58 chunk 的基线卡 74 chunk 的 run，这里会当场露馅。
$info = & $py baseline_probe.py $baseline
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] 基线指纹读取失败（$Baseline 不是合法的 run json？）" -ForegroundColor Red
    exit 1
}
foreach ($line in $info) { Write-Host "  [baseline_source] $line" -ForegroundColor DarkGray }

& $py compare.py --base $baseline --new runs\current.json `
    --gate "hit@5>=-$MAX_DROP" --gate "hit@10>=-$MAX_DROP"
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAIL] 闸门未通过：召回退步超过 $MAX_DROP pp（基线 $Baseline）" -ForegroundColor Red
    exit 1
}

Write-Host "`n[PASS] 全部闸门通过（基线 $Baseline，容忍退步 <= $MAX_DROP pp）" -ForegroundColor Green

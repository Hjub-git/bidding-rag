#  向量库（Chroma HNSW）一键体检 / 修复
#
#  什么时候用：
#    问答接口报 `Error loading hnsw index`（或 /api/ingest 报"向量库读取失败"），
#    说明 chromadb 1.5.9 把 <segment-uuid>/ 下的 HNSW 索引写坏了。本脚本：
#      体检 → 坏库归档（移动，不删）→ 从历史备份恢复 → 重开校验。
#
#  用法（在任意目录；路径由 $PSScriptRoot 推导，仓库搬到哪都能跑）：
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-vector.ps1 -DryRun
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-vector.ps1
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-vector.ps1 -Backup vector_data.bak_T08_pre
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\repair-vector.ps1 -Force -Backup <名字>
#
#  可选参数：
#    -DryRun        只体检，不做任何写操作（也不在原地开生产库，见"只读口径"）
#    -Backup <名>   指定 _archive\vector_backups 下的某一份备份（默认自动挑）
#    -Force         允许用"未通过 embeddings_queue >= embeddings 校验"的备份（自担风险）
#    -Python <exe>  Python 解释器（默认与 start-all.ps1 同一个，可用 $env:RAG_PYTHON 覆盖）
#
#  退出码：0 = 健康或已修复；1 = 检出损坏但未修复 / 修复失败；
#          2 = 前置条件不满足（缺 Python / 缺库目录 / 端口被占 / -Backup 非法）；
#          3 = 环境缺 chromadb（import 失败）或探针没输出。
#
# 只读口径（重要）
#  生产库 `rag-service\vector_data` 是 734 chunk 的演示资产，不要对它做无谓的写。
#  chromadb 只要 PersistentClient(path=...) 就会建 -wal/-shm、跑 migration，
#  所以体检阶段不在原地开生产库：
#    ① 静态体检：sqlite 以 `mode=ro` 打开（纯读），+ 段目录文件清点；
#    ② 开库体检：把库复制到 %TEMP% 下的临时目录再开，count() + 一次 query。
#  校验的是同一份 byte 数据（含"段目录为空 → 从 sqlite 重放重建"这条路径），
#  生产库一个字节都不动。只有已判定"坏 + 非 DryRun"时，才在真库上做文件操作。
#
# 它不做什么
#  不启动/停止任何服务（所以结束时 8000/8080 必须本来就是空的）；
#  不删 `_archive\` 里的任何东西（坏库是移动进去的取证材料）；
#  不做"count 正常就删 bin"的销毁式自愈（WAL 被 compaction 裁剪过就拒绝重建，
#  与 rag_service._ensure_vector_db_healthy 的判定口径一致）。
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$Backup = '',
    [switch]$Force,
    [string]$Python = $(if ($env:RAG_PYTHON) { $env:RAG_PYTHON } else { 'C:\Users\32571\AppData\Local\Programs\Python\Python39\python.exe' })
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

# 路径（由脚本位置推出来，跟着工程走）
# scripts/ 的上一级就是仓库根：$root
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) { $root = (Get-Location).Path }
$vdDir  = Join-Path $root 'rag-service\vector_data'         # 生产库
$bkDir  = Join-Path $root '_archive\vector_backups'         # 历史备份 + 坏库归档（进 .gitignore）
$runDir = Join-Path $root '_run'                            # 日志（进 .gitignore）
$proj   = $root                                             # 兼容下文对 $proj 的引用
$script:LogPath   = Join-Path $runDir 'repair-vector.log'
$script:probeFile = $null
$script:tmpRoot   = $null

New-Item -ItemType Directory -Path $runDir -Force | Out-Null

# 小工具（风格与 start-all.ps1 / stop-all.ps1 一致）
function Say([string]$msg) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss.fff'), $msg
    Write-Host $line
    try {
        [System.IO.File]::AppendAllText($script:LogPath, $line + "`r`n",
            (New-Object System.Text.UTF8Encoding($false)))
    } catch { }
}

function Die([int]$code, [string]$msg) {
    Say "[FAIL] $msg"
    Say ("RESULT=FAIL exit={0}" -f $code)
    exit $code
}

function Get-ListenerCount([int]$port) {
    return @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count
}

function Get-PortPid([int]$port) {
    $c = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    if ($c.Count -gt 0) { return [int]$c[0].OwningProcess }
    return 0
}

function Format-Bytes([long]$n) {
    if ($n -ge 1GB) { return ('{0:N1} GB' -f ($n / 1GB)) }
    if ($n -ge 1MB) { return ('{0:N1} MB' -f ($n / 1MB)) }
    if ($n -ge 1KB) { return ('{0:N0} KB' -f ($n / 1KB)) }
    return ('{0} B' -f $n)
}

# 段文件五件套：chromadb 1.5.9 先写 index_metadata.pickle，再写四个 .bin
$SCRIPT:SEG_FILES = @('index_metadata.pickle', 'data_level0.bin', 'header.bin',
                      'length.bin', 'link_lists.bin')

# 内嵌 Python 探针（体检 / 备份扫描 / 静态读 都走它，逻辑只有一份）
#  三种模式，各自以 `META ` / `SCAN ` / `PROBE ` 前缀打一行 JSON，PS 侧只认这一行：
#    meta  <库目录>     只读打开 chroma.sqlite3（mode=ro）+ 清点段目录，不开 chroma
#    scan  <备份根目录> 逐个备份输出 embeddings / queue / 段目录状态
#    probe <库目录>     开库 count() + 一次 query（会写 -wal/-shm，只许对副本/刚恢复的库用）
$probePython = @'
# -*- coding: utf-8 -*-
"""repair-vector.ps1 的内嵌探针：单次统计，不常驻。"""
import json
import os
import sqlite3
import sys

ZERO_VEC = 0.01          # 非零：cosine 空间下 0 向量归一化会出 nan

# 段目录五件套："只有 pickle、缺 bin" 就是写盘被中断留下的坏索引
SEG_FILES = ("index_metadata.pickle", "data_level0.bin", "header.bin",
             "length.bin", "link_lists.bin")


def read_meta(db):
    """只读打开 chroma.sqlite3，取集合名 / 维度 / embeddings 与 WAL 行数。"""
    m = {"collections": [], "name": "", "dimension": 0, "embeddings": 0, "queue": 0}
    if not os.path.isfile(db):
        return m
    uri = "file:" + db.replace(os.sep, "/") + "?mode=ro"
    con = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        rows = con.execute("SELECT name, dimension FROM collections").fetchall()
        m["collections"] = [r[0] for r in rows]
        if rows:
            m["name"] = rows[0][0] or ""
            try:
                m["dimension"] = int(rows[0][1] or 0)
            except Exception:
                m["dimension"] = 0
        m["embeddings"] = int(con.execute("SELECT COUNT(*) FROM embeddings").fetchone()[0])
        m["queue"] = int(con.execute("SELECT COUNT(*) FROM embeddings_queue").fetchone()[0])
    finally:
        con.close()
    return m


def seg_state(lib_dir):
    """返回 {段目录名: 状态}。只有 pickle_only / partial 是坏的（沙箱里实测）：

      empty       段目录为空 —— 正常：向量在 embeddings_queue 里，冷启动重放重建
      ok          五件套齐 —— 正常
      bins_only   只有 .bin、没有 index_metadata.pickle —— 正常：chromadb 重建索引
                  后就是这个形态（pickle 到 close/persist 才写），实测 count/query 都通
      pickle_only 只有 index_metadata.pickle —— 坏：写盘被中断，开库/查询抛
                  `Error loading hnsw index`（生产 808_bad、沙箱伪造样本都是这形态）
      partial     有 pickle + 部分 bin —— 坏
    """
    out = {}
    try:
        names = os.listdir(lib_dir)
    except OSError:
        return out
    for name in sorted(names):
        d = os.path.join(lib_dir, name)
        if name.startswith("_") or not os.path.isdir(d):
            continue
        have = [f for f in SEG_FILES if os.path.isfile(os.path.join(d, f))]
        if not have:
            out[name] = "empty"
        elif len(have) == len(SEG_FILES):
            out[name] = "ok"
        elif have == ["index_metadata.pickle"]:
            out[name] = "pickle_only"
        elif "index_metadata.pickle" not in have:
            out[name] = "bins_only"
        else:
            out[name] = "partial"
    return out


def emit(tag, payload, code=0):
    print(tag + " " + json.dumps(payload, ensure_ascii=False))
    sys.stdout.flush()
    sys.exit(code)


def do_meta(path):
    """纯静态：只读 sqlite + 清点段目录，绝不 import chromadb。"""
    res = {"dir": path, "collection": "", "dimension": 0, "embeddings": 0,
           "queue": 0, "segments": {}, "has_db": False,
           "has_map": os.path.isfile(os.path.join(path, "doc_chunk_map.json")), "error": ""}
    db = os.path.join(path, "chroma.sqlite3")
    res["has_db"] = os.path.isfile(db)
    if not os.path.isdir(path):
        res["error"] = "库目录不存在：" + path
        emit("META", res, 1)
    if res["has_db"]:
        try:
            meta = read_meta(db)
            res["collection"] = meta["name"]
            res["dimension"] = meta["dimension"]
            res["embeddings"] = meta["embeddings"]
            res["queue"] = meta["queue"]
        except Exception as exc:
            res["error"] = "读 chroma.sqlite3 失败 %s: %s" % (type(exc).__name__, exc)
    else:
        res["error"] = "缺少 chroma.sqlite3，这不是向量库目录"
    res["segments"] = seg_state(path)
    emit("META", res, 0 if (res["has_db"] and not res["error"]) else 1)


def do_scan(root):
    rows = []
    try:
        names = sorted(os.listdir(root))
    except OSError as exc:
        emit("SCAN", {"error": str(exc)}, 1)
    for name in names:
        d = os.path.join(root, name)
        if not os.path.isdir(d):
            continue
        db = os.path.join(d, "chroma.sqlite3")
        if not os.path.isfile(db):
            continue
        row = {"name": name, "dir": d, "mtime": os.path.getmtime(d),
               "has_map": os.path.isfile(os.path.join(d, "doc_chunk_map.json")),
               "segments": seg_state(d), "error": ""}
        try:
            meta = read_meta(db)
            row["collection"] = meta["name"]
            row["dimension"] = meta["dimension"]
            row["embeddings"] = meta["embeddings"]
            row["queue"] = meta["queue"]
        except Exception as exc:
            row["error"] = "%s: %s" % (type(exc).__name__, exc)
            row["embeddings"] = row["queue"] = 0
        rows.append(row)
    emit("SCAN", rows, 0)


def do_probe(path):
    res = {"ok": False, "count": None, "error": "", "stage": "meta", "dir": path,
           "collection": "", "dimension": 0, "embeddings": 0, "queue": 0, "segments": {}}
    if not os.path.isdir(path):
        res["error"] = "库目录不存在：" + path
        emit("PROBE", res, 1)

    db = os.path.join(path, "chroma.sqlite3")
    try:
        meta = read_meta(db)
    except Exception as exc:
        res["error"] = "读 chroma.sqlite3 失败 %s: %s" % (type(exc).__name__, exc)
        emit("PROBE", res, 1)
    res.update({"collection": meta["name"], "dimension": meta["dimension"],
                "embeddings": meta["embeddings"], "queue": meta["queue"]})
    res["segments"] = seg_state(path)
    if not os.path.isfile(db):
        res["error"] = "缺少 chroma.sqlite3，这不是向量库目录"
        emit("PROBE", res, 1)

    try:
        import chromadb
    except Exception as exc:
        res["error"] = "import chromadb 失败 %s: %s（用 -Python 指向装了 chromadb 的解释器）" % (
            type(exc).__name__, exc)
        emit("PROBE", res, 3)

    try:
        res["stage"] = "open"
        client = chromadb.PersistentClient(path=path)
        names = [getattr(c, "name", None) or str(c) for c in client.list_collections()]
        if not names:
            res.update({"ok": True, "count": 0, "error": ""})
            emit("PROBE", res, 0)
        res["stage"] = "count"
        col = client.get_collection(meta["name"] or names[0])
        n = col.count()
        res["count"] = n
        if n > 0:
            # 必须真查一次：索引被重建/写坏时 count 与 get 都可能正常，
            # 只有 query 才抛 `Error loading hnsw index` / `Error finding id`。
            res["stage"] = "query"
            dim = meta["dimension"] or 512
            col.query(query_embeddings=[[ZERO_VEC] * dim], n_results=1)
        res.update({"ok": True, "error": ""})
        emit("PROBE", res, 0)
    except Exception as exc:
        res["error"] = "%s: %s" % (type(exc).__name__, exc)
        emit("PROBE", res, 1)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "probe"
    arg = sys.argv[2] if len(sys.argv) > 2 else ""
    if mode == "meta":
        return do_meta(arg)
    if mode == "scan":
        return do_scan(arg)
    return do_probe(arg)


main()
'@

function Initialize-Probe {
    if ($script:probeFile -and (Test-Path -LiteralPath $script:probeFile)) { return }
    $script:tmpRoot = Join-Path $env:TEMP ('repair-vector-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $script:tmpRoot -Force | Out-Null
    $script:probeFile = Join-Path $script:tmpRoot '_probe.py'
    # 无 BOM 写入（Python 3 容忍 BOM，但没必要）
    [System.IO.File]::WriteAllText($script:probeFile, $probePython,
        (New-Object System.Text.UTF8Encoding($false)))
}

function Remove-Probe {
    if (-not $script:tmpRoot) { return }
    for ($i = 1; $i -le 5; $i++) {
        try {
            if (Test-Path -LiteralPath $script:tmpRoot) {
                Remove-Item -LiteralPath $script:tmpRoot -Recurse -Force -ErrorAction Stop
            }
            return
        } catch {
            Start-Sleep -Milliseconds 300        # Windows 上偶发句柄未释放
        }
    }
    Say ("  [WARN] 临时目录未能删除（不影响向量库），可手工清理：{0}" -f $script:tmpRoot)
}

function Invoke-Probe {
    <# 跑一次探针：@{ code; json; raw }。json=$null 表示没拿到结构化结果。#>
    param([string]$Mode, [string]$Target)
    Initialize-Probe          # 探针文件被临时清理掉时自动重建
    $env:PYTHONIOENCODING      = 'utf-8'
    $env:PYTHONUTF8            = '1'
    $env:HF_HUB_OFFLINE        = '1'
    $env:TRANSFORMERS_OFFLINE  = '1'
    $env:ANONYMIZED_TELEMETRY  = 'False'
    $raw = ''
    $code = 1
    try {
        $raw = (& $Python -X utf8 $script:probeFile $Mode $Target 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } catch {
        $raw = "$raw`n$($_.Exception.Message)"
    }
    $prefix = $Mode.ToUpper() + ' '
    $line = @($raw -split "`r?`n" | Where-Object { $_.StartsWith($prefix) } | Select-Object -Last 1)
    $json = $null
    if ($line.Count -gt 0 -and $line[0]) {
        try { $json = ($line[0].Substring($prefix.Length) | ConvertFrom-Json) } catch { $json = $null }
    }
    return [pscustomobject]@{ code = $code; json = $json; raw = $raw }
}

function Show-Raw([string]$raw, [int]$last = 12) {
    Say '  探针原始输出（末几行）：'
    @($raw -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -Last $last) |
        ForEach-Object { Say ("    {0}" -f $_) }
}

function Format-SegSummary($segments) {
    if (-not $segments) { return '（无段目录）' }
    $parts = @()
    foreach ($p in $segments.PSObject.Properties) {
        $n = [Math]::Min(8, $p.Name.Length)
        $parts += ('{0}={1}' -f $p.Name.Substring(0, $n), $p.Value)
    }
    if ($parts.Count -eq 0) { return '（无段目录）' }
    return ($parts -join ' ')
}

function Test-SegBroken($segments) {
    # 只有这两种是真坏：pickle_only（写盘中断，实测抛 Error loading hnsw index）、
    # partial（有 pickle 缺 bin）。bins_only 不算坏（见探针 seg_state 的说明）。
    $bad = @()
    if ($segments) {
        foreach ($p in $segments.PSObject.Properties) {
            if (@('pickle_only', 'partial') -contains $p.Value) {
                $bad += ('{0}（{1}）' -f $p.Name.Substring(0, [Math]::Min(8, $p.Name.Length)), $p.Value)
            }
        }
    }
    return $bad
}

function Test-SegWarn($segments) {
    $warn = @()
    if ($segments) {
        foreach ($p in $segments.PSObject.Properties) {
            if ($p.Value -eq 'bins_only') {
                $warn += ('{0}（{1}）' -f $p.Name.Substring(0, [Math]::Min(8, $p.Name.Length)), $p.Value)
            }
        }
    }
    return $warn
}

function Get-LeftoverProbes {
    return @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*_probe.py*' })
}

# ══════════════════════════════════════════════════════════════════════════
#  [0/6] 前置
# ══════════════════════════════════════════════════════════════════════════
Say '══════════════════════════════════════════════════════════'
Say ("repair-vector  {0}" -f $(if ($DryRun) { 'DRY-RUN（只体检，不写任何东西）' } else { '修复模式' }))
Say '══════════════════════════════════════════════════════════'
Say ("  仓库根  {0}" -f $root)
Say ("  生产库  {0}" -f $vdDir)
Say ("  备份区  {0}" -f $bkDir)
Say ("  Python  {0}" -f $Python)

Say '[0/6] 前置检查'
if (-not (Test-Path -LiteralPath $Python)) {
    Die 2 ("找不到 Python：{0}（用 -Python <exe> 指定装了 chromadb 的那个）" -f $Python)
}
Say ("  python  OK  {0}" -f ((& $Python -V 2>&1) -join ' '))
if (-not (Test-Path -LiteralPath $vdDir)) {
    Die 2 ("生产库目录不存在：{0}" -f $vdDir)
}
if (-not (Test-Path -LiteralPath $bkDir)) {
    Say ("  [WARN] 备份区不存在（{0}）：只能体检，不能自动修复" -f $bkDir)
}

# 服务必须先停：一边跑一边搬/开文件 = 双开库，必坏
$pyPort = Get-PortPid 8000
if ($pyPort -ne 0) {
    $health = 'n/a'
    try {
        # 本机 curl 是 PowerShell 别名，必须写 curl.exe
        $health = (& curl.exe -s -m 2 -o NUL -w '%{http_code}' 'http://127.0.0.1:8000/api/health' 2>&1 | Out-String).Trim()
    } catch { }
    $msg = "8000 上还有服务在跑（pid={0}，/api/health={1}）：先跑 stop-all.ps1。运行中搬/开向量库会把它写坏。" -f $pyPort, $health
    Die 2 $msg
}
$javaPort = Get-PortPid 8080
if ($javaPort -ne 0) {
    Say ("  [WARN] 8080 有监听（pid={0}）：Java 不持有 chroma 文件，继续" -f $javaPort)
}
Say '  端口    8000 空闲'

Initialize-Probe
Say ("  探针    {0}" -f $script:probeFile)

# ══════════════════════════════════════════════════════════════════════════
#  [1/6] 静态体检（纯只读：sqlite mode=ro + 段目录清点，不开 chroma）
# ══════════════════════════════════════════════════════════════════════════
Say '[1/6] 静态体检（只读：chroma.sqlite3 以 mode=ro 打开 + 段目录清点）'
$metaR = Invoke-Probe 'meta' $vdDir
if (-not $metaR.json) {
    Show-Raw $metaR.raw
    Remove-Probe
    Die 3 '静态体检没拿到 META 行（Python 环境/编码问题）'
}
$m = $metaR.json
$dbFile = Join-Path $vdDir 'chroma.sqlite3'
$dbBytes = if (Test-Path -LiteralPath $dbFile) { (Get-Item -LiteralPath $dbFile).Length } else { 0 }
Say ("  chroma.sqlite3    {0}" -f $(if ($dbBytes -gt 0) { Format-Bytes $dbBytes } else { '缺失' }))
Say ("  doc_chunk_map.json {0}" -f $(if ($m.has_map) { '在' } else { '缺失（可由向量库 metadata 重建）' }))
Say ("  集合              {0}  维度={1}" -f $(if ($m.collection) { $m.collection } else { '(无)' }), $m.dimension)
Say ("  sqlite            embeddings={0}  embeddings_queue={1}  {2}" -f $m.embeddings, $m.queue,
    $(if ($m.embeddings -gt 0 -and $m.queue -ge $m.embeddings) { '（WAL 可重放 → 删索引安全）' } else { '（WAL 已被裁剪 → 不可重放）' }))
Say ("  段目录            {0}" -f (Format-SegSummary $m.segments))
$staticSegBad = @(Test-SegBroken $m.segments)
$segWarn = @(Test-SegWarn $m.segments)
if ($staticSegBad.Count -gt 0) {
    Say ("  [BAD] 残缺段目录：{0}（缺 bin 的段任何 get/query 都会抛 Error loading hnsw index）" -f ($staticSegBad -join '、'))
} elseif ($segWarn.Count -gt 0) {
    Say ("  [OK] 段目录可用：{0}（只有 .bin 没有 pickle 属正常：pickle 到 close/persist 才写）" -f ($segWarn -join '、'))
} else {
    Say '  [OK] 段目录无残件（空目录也正常：冷启动按 embeddings_queue 重放重建）'
}

# ══════════════════════════════════════════════════════════════════════════
#  [2/6] 开库体检（在 %TEMP% 副本上：count() + 一次 query）
# ══════════════════════════════════════════════════════════════════════════
Say '[2/6] 开库体检（副本路径 → 生产库零写入）'
$probeDir = Join-Path $script:tmpRoot 'vd'
Say ("  复制 {0}" -f $probeDir)
$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    Copy-Item -LiteralPath $vdDir -Destination $probeDir -Recurse -Force -ErrorAction Stop
} catch {
    Remove-Probe
    Die 2 ("复制生产库失败（体检中止，原库未动）：{0}" -f $_.Exception.Message)
}
$sw.Stop()
$copied = @(Get-ChildItem -LiteralPath $probeDir -Recurse -File -ErrorAction SilentlyContinue).Count
Say ("  复制完成 {0} 个文件 / {1:N2}s" -f $copied, $sw.Elapsed.TotalSeconds)

$sw = [Diagnostics.Stopwatch]::StartNew()
$pr = Invoke-Probe 'probe' $probeDir
$sw.Stop()
if (-not $pr.json) {
    Show-Raw $pr.raw
    Remove-Probe
    Die 3 '开库体检没拿到 PROBE 行（Python 环境/编码问题）'
}
if ($pr.code -eq 3) {
    Remove-Probe
    Die 3 ('环境缺 chromadb：' + $pr.json.error)
}
$p = $pr.json
Say ("  开库    count={0}  stage={1}  {2:N2}s" -f $(if ($null -ne $p.count) { $p.count } else { '—' }),
    $p.stage, $sw.Elapsed.TotalSeconds)
if ($p.ok -and $p.stage -eq 'query') {
    Say '  查询    OK（已用 0.01 占位向量真查一次）'
} elseif ($p.ok) {
    Say '  查询    跳过（空库，无可查向量）'
} else {
    Say ("  查询    FAIL [{0}] {1}" -f $p.stage, $p.error)
}

$healthy = $true
$reasons = @()
if (-not $p.ok) {
    $healthy = $false
    $reasons += ('开库/查询失败 [{0}] {1}' -f $p.stage, $p.error)
}
if ($staticSegBad.Count -gt 0) {
    $healthy = $false
    $reasons += ('存在残缺段目录：' + ($staticSegBad -join '、'))
}
if ($p.ok -and $null -ne $p.count -and $m.embeddings -gt 0 -and $p.count -ne $m.embeddings) {
    $healthy = $false
    $reasons += ('count({0}) 与 sqlite embeddings({1}) 不一致' -f $p.count, $m.embeddings)
}
if ($p.ok -and $m.embeddings -gt 0 -and $m.queue -lt $m.embeddings) {
    Say '  [WARN] WAL 行数 < embeddings：索引一旦重建，向量就重放不出来（别删 bin，交给本脚本走备份恢复）'
}

# 终检 + 收尾（健康分支与 DryRun 分支共用）
function Complete-Run([string]$result, [int]$code) {
    $c8000 = Get-ListenerCount 8000
    $c8080 = Get-ListenerCount 8080
    Say ("[6/6] 端口终检（本脚本不启服务）  8000 监听={0}  8080 监听={1}" -f $c8000, $c8080)
    $leftover = @(Get-LeftoverProbes)
    if ($leftover.Count -gt 0) {
        Say ("  [WARN] 残留探针进程：{0}" -f (($leftover | ForEach-Object { $_.ProcessId }) -join ','))
        $code = 1
        $result = 'FAIL'
    }
    if ($c8000 -gt 0) {
        Say '  [FAIL] 8000 仍有监听（不是本脚本起的；查 stop-all.ps1）'
        $code = 1
        $result = 'FAIL'
    }
    Say ("RESULT={0} exit={1}" -f $result, $code)
    exit $code
}

if ($healthy) {
    Say '[3/6] 判定：健康，无需修复'
    Say ("OK count={0}" -f $p.count)
    if ($DryRun) { Say 'DRY-RUN：未做任何写操作（生产库只在副本上开过）' }
    Remove-Probe
    Complete-Run 'PASS' 0
}

Say '[3/6] 判定：不健康'
foreach ($r in $reasons) { Say ("  - {0}" -f $r) }

if ($DryRun) {
    Say 'DRY-RUN：只报告，不动任何文件。去掉 -DryRun 即执行修复，预计动作：'
    Say ("    1) 移动 {0} → {1}\vector_data.corrupted_<时间戳>" -f $vdDir, $bkDir)
    Say  '    2) 复制「queue>=embeddings 的最新备份」回 rag-service\vector_data'
    Say  '    3) 把残缺段目录（有 pickle 缺 bin）隔离到 <库>\_hnsw_broken\<时间戳>\'
    Say  '    4) 原地重开校验 count() + query'
    Say ("BAD count={0} stage={1} error={2}" -f $(if ($null -ne $p.count) { $p.count } else { '—' }), $p.stage, $p.error)
    Remove-Probe
    Complete-Run 'BAD' 1
}

# ══════════════════════════════════════════════════════════════════════════
#  [4/6] 挑备份（口径：embeddings>0 且 embeddings_queue >= embeddings，取最新）
# ══════════════════════════════════════════════════════════════════════════
Say '[4/6] 挑选备份'
$scanR = Invoke-Probe 'scan' $bkDir
if (-not $scanR.json) {
    Show-Raw $scanR.raw
    Remove-Probe
    Die 3 '扫描备份区失败（没拿到 SCAN 行）'
}
$cands = @($scanR.json)
if ($cands.Count -eq 0) {
    Remove-Probe
    Die 2 ("备份区里没有任何 chroma.sqlite3：{0}" -f $bkDir)
}
foreach ($c in $cands) {
    $verdict = if ($c.embeddings -gt 0 -and $c.queue -ge $c.embeddings) { 'REPLAYABLE' } else { 'not-replayable' }
    Say ("  {0,-42} emb={1,-5} queue={2,-5} {3}  segs:{4}" -f [string]$c.name, $c.embeddings, $c.queue,
        $verdict, (Format-SegSummary $c.segments))
}

$usable = @($cands | Where-Object { $_.embeddings -gt 0 -and $_.queue -ge $_.embeddings })
$pick = $null
if ($Backup) {
    $hit = @($cands | Where-Object { $_.name -eq $Backup })
    if ($hit.Count -eq 0) {
        Remove-Probe
        Die 2 ("-Backup 指定的备份不存在或不是向量库：{0}" -f $Backup)
    }
    $pick = $hit[0]
    if (-not ($pick.embeddings -gt 0 -and $pick.queue -ge $pick.embeddings)) {
        if (-not $Force) {
            $m2 = "备份 {0} 未通过 queue>=embeddings 校验（emb={1} queue={2}）：恢复后重建索引会丢向量。确认可用加 -Force。" -f $pick.name, $pick.embeddings, $pick.queue
            Remove-Probe
            Die 2 $m2
        }
        Say ("  [WARN] -Force：使用未通过校验的备份 {0}" -f $pick.name)
    } else {
        Say ("  → 指定 {0}（emb={1} queue={2}）" -f $pick.name, $pick.embeddings, $pick.queue)
    }
} else {
    if ($usable.Count -eq 0) {
        Remove-Probe
        Die 2 '没有一份备份通过 queue>=embeddings 校验，无法自动修复（可 -Backup + -Force，或重新 ingest）'
    }
    $pick = @($usable | Sort-Object -Property @{ Expression = { $_.mtime } } -Descending)[0]
    Say ("  → 选中 {0}（emb={1} queue={2}，最新的可重放备份）" -f $pick.name, $pick.embeddings, $pick.queue)
    if ($m.embeddings -gt 0 -and $pick.embeddings -ne $m.embeddings) {
        Say ("  [WARN] 备份 chunk 数({0}) ≠ 当前库({1})：恢复后文档数会变，确认这就是想要的版本" -f $pick.embeddings, $m.embeddings)
    }
}

# ══════════════════════════════════════════════════════════════════════════
#  [5/6] 执行修复
# ══════════════════════════════════════════════════════════════════════════
Say '[5/6] 执行修复'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$corruptDest = Join-Path $bkDir ('vector_data.corrupted_' + $stamp)
$n = 1
while (Test-Path -LiteralPath $corruptDest) {
    $corruptDest = Join-Path $bkDir ('vector_data.corrupted_{0}_{1}' -f $stamp, $n)
    $n++
}

Say ("  1) 移动坏库（移动，不删除）→ {0}" -f $corruptDest)
try {
    Move-Item -LiteralPath $vdDir -Destination $corruptDest -ErrorAction Stop
} catch {
    Remove-Probe
    Die 1 ("移动坏库失败（原库未动）：{0}" -f $_.Exception.Message)
}
Say '     done'

Say ("  2) 复制备份 {0} → 生产库位置" -f $pick.name)
try {
    Copy-Item -LiteralPath $pick.dir -Destination $vdDir -Recurse -Force -ErrorAction Stop
} catch {
    Say ("  [FAIL] 复制备份失败：{0}" -f $_.Exception.Message)
    Say ("  坏库已归档在 {0}；可手工把 {1} 复制回 {2}" -f $corruptDest, $pick.dir, $vdDir)
    Remove-Probe
    Die 1 '修复失败（库目录当前可能为空）'
}
Say '     done'

Say '  3) 隔离残缺段目录（有 pickle 缺 bin 的 → _hnsw_broken；等价"删坏段"但可回滚）'
$quarantine = Join-Path $vdDir ('_hnsw_broken\' + $stamp)
$qMoved = 0
foreach ($seg in @(Get-ChildItem -LiteralPath $vdDir -Directory -ErrorAction SilentlyContinue)) {
    if ($seg.Name.StartsWith('_')) { continue }
    $have = @()
    foreach ($f in $SCRIPT:SEG_FILES) {
        if (Test-Path -LiteralPath (Join-Path $seg.FullName $f)) { $have += $f }
    }
    # 只有"有 pickle 但不足五件套"才是坏段；"只有 bin 没有 pickle"是 chromadb
    # 重建后的正常形态，动了反而丢索引（沙箱里实测）。
    $hasPickle = $have -contains 'index_metadata.pickle'
    if ($hasPickle -and $have.Count -lt 5) {
        New-Item -ItemType Directory -Path $quarantine -Force | Out-Null
        $target = Join-Path $quarantine $seg.Name
        try {
            Move-Item -LiteralPath $seg.FullName -Destination $target -ErrorAction Stop
            $qMoved++
            Say ("     {0}  只有 [{1}] → 隔离到 {2}" -f $seg.Name, ($have -join ','), $target)
        } catch {
            Say ("     [WARN] 隔离 {0} 失败：{1}" -f $seg.Name, $_.Exception.Message)
        }
    } else {
        Say ("     {0}  文件数={1} → 保留" -f $seg.Name, $have.Count)
    }
}
if ($qMoved -eq 0) { Say '     无需隔离（空段目录 / 只有 bin 的段都属正常，冷启动从 sqlite 重放重建）' }

Say '  4) 原地重开校验（会重建一次索引：1–2s）'
$sw = [Diagnostics.Stopwatch]::StartNew()
$ar = Invoke-Probe 'probe' $vdDir
$sw.Stop()
if (-not $ar.json) {
    Show-Raw $ar.raw
    Say ("  [FAIL] 重开校验没拿到结构化结果；坏库归档在 {0}" -f $corruptDest)
    Remove-Probe
    Die 1 '修复失败'
}
$a = $ar.json
Say ("  集合={0} dim={1}  段目录 {2}" -f $a.collection, $a.dimension, (Format-SegSummary $a.segments))
Say ("  count={0}  query={1}  {2:N2}s" -f $(if ($null -ne $a.count) { $a.count } else { '—' }),
    $(if ($a.ok -and $a.count -gt 0) { 'OK' } elseif ($a.ok) { '空库' } else { 'FAIL' }), $sw.Elapsed.TotalSeconds)

if (-not ($a.ok -and $null -ne $a.count -and $a.embeddings -eq $a.count)) {
    Say ("  [FAIL] 恢复后仍不可用 [{0}] {1}" -f $a.stage, $a.error)
    Say ("  坏库归档：{0}；本次恢复来源：{1}" -f $corruptDest, $pick.dir)
    Say '  回滚：停服务 → 把归档目录移回 rag-service\vector_data（先移走当前目录）→ 重跑 -DryRun 确认'
    Remove-Probe
    Die 1 '修复失败'
}

# 重建后的落盘形态（重建当场往往还没有 index_metadata.pickle，属正常）
$postR = Invoke-Probe 'meta' $vdDir
$postSeg = if ($postR.json) { Format-SegSummary $postR.json.segments } else { '（读不到）' }
Say ("  修复后段目录  {0}" -f $postSeg)
Remove-Probe
Say ("  坏库归档  {0}" -f $corruptDest)
Say ("  恢复来源  {0}" -f $pick.dir)
Say ("REPAIRED count={0}" -f $a.count)
Say '提示：后续请逐个上传文件，不要连续删（chromadb 1.5.9 在 734~1615 区间写坏 HNSW，见 README「已知限制」）。'
Complete-Run 'PASS' 0

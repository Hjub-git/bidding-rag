# -*- coding: utf-8 -*-
"""闸门辅助：打印基线 run 的「口径指纹」（哪套库 / 哪套题集 / 多少题 / 指标 / CI / sha256）。

为什么要单独成文件，而不是写在 gate.ps1 里用 `python -c`
----------------------------------------------------------
PowerShell 5.1 调原生 exe 时会把参数里的双引号剥掉，多行 `python -c "..."`
必然被改写成语法错（实测：`print("a=%s" % x)` 变成 `print(a=%s % x)`，
报 SyntaxError: expression cannot contain assignment）。落成文件只传路径，
不受引号转义影响，5.1 / 7 都能跑。

用法：
    python baseline_probe.py baseline_seed80.json
"""
import hashlib
import json
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from _common import wilson95
except Exception:  # _common 被挪走也不该让闸门挂掉
    wilson95 = None


def _ci(m, d, ci_key, metric_key, n):
    """优先读 run 里存的 CI；早先生成的基线没存，就用命中数反推。

    hit@5/hit@10 是 1 位小数，k = round(hit/100*n) 在 n<=200 时不会猜错
    （真值必为整数，量化误差 < 0.5/n）。
    """
    v = m.get(ci_key) or d.get(ci_key)
    if v:
        return v
    if not wilson95 or not n:
        return "-"
    hit = m.get(metric_key)
    if hit is None:
        return "-"
    k = int(round(float(hit) * n / 100.0))
    return f"{wilson95(k, n)} (反推 {k}/{n})"


def main():
    if len(sys.argv) < 2:
        print("usage: python baseline_probe.py <run.json>")
        return 2
    path = sys.argv[1]

    with open(path, encoding="utf-8") as f:
        raw = f.read()
    d = json.loads(raw)
    m = d.get("metrics", {}) or {}

    def g(*keys, default="-"):
        for k in keys:
            if k in m and m[k] is not None:
                return m[k]
            if k in d and d[k] is not None:
                return d[k]
        return default

    n = m.get("n_answerable", d.get("n_answerable"))
    print(f"file={path}")
    print(f"chunk_total={g('chunk_total')}")
    print(f"golden={g('golden_version')}")
    print(f"n_answerable={n}")
    print(f"hit@5={g('hit@5')}  hit@10={g('hit@10')}")
    print(f"hit5_ci={_ci(m, d, 'hit5_ci', 'hit@5', n)}")
    print(f"hit10_ci={_ci(m, d, 'hit10_ci', 'hit@10', n)}")
    print(f"sha256={hashlib.sha256(raw.encode('utf-8')).hexdigest()[:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

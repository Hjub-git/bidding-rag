# -*- coding: utf-8 -*-
"""闸门：对比两次 run，出 Δ 与 flip 表，任一 gate 不满足则退出码 1。

用法：
    python compare.py --base baseline.json --new c0_5.json --gate "hit@10>=-2"
    python compare.py --base a.json --new b.json --gate "hit@10>=-2" --gate "leak_count==0"

gate 语法：<metric><op><value>，op ∈ >= <= > < == !=
metric 取自 run json 的 metrics 或顶层字段（leak_count / chunk_total / hit@10 ...）
Δ 的符号约定：正值 = 变好（对 hit 类是 new-base；对 leak/延迟类是 base-new 的直觉化不做，保持字面差）
"""
import json
import sys
import argparse

# 中文 Windows 控制台默认 GBK，直接 print 非 GBK 字符会 UnicodeEncodeError
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

OPS = {
    ">=": lambda a, b: a >= b,
    "<=": lambda a, b: a <= b,
    ">": lambda a, b: a > b,
    "<": lambda a, b: a < b,
    "==": lambda a, b: a == b,
    "!=": lambda a, b: a != b,
}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def metric_of(run, name):
    if name in run:
        return run[name]
    return run.get("metrics", {}).get(name)


def parse_gate(expr):
    for op in ("!=", ">=", "<=", "==", ">", "<"):
        if op in expr:
            left, right = expr.split(op, 1)
            return left.strip(), op, float(right)
    raise ValueError(f"bad gate: {expr}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--gate", action="append", default=[])
    args = ap.parse_args()

    base, new = load(args.base), load(args.new)

    metrics = ["hit@5", "hit@10", "all@10", "ecr@10_mean", "latency_p50_ms"]
    print(f"{'metric':<18}{'base':>10}{'new':>10}{'delta':>10}")
    print("-" * 48)
    for m in metrics:
        b, n = metric_of(base, m), metric_of(new, m)
        if b is None or n is None:
            continue
        d = n - b
        flag = "  ← 退步" if ("hit" in m or "ecr" in m) and d < 0 else ""
        print(f"{m:<18}{b:>10}{n:>10}{d:>+10.1f}{flag}")

    # flip 表
    bi = {r["id"]: r for r in base.get("per_item", []) if r.get("kind", "answer") == "answer"}
    ni = {r["id"]: r for r in new.get("per_item", []) if r.get("kind", "answer") == "answer"}
    gained = [k for k in bi if k in ni and ni[k].get("hit10") and not bi[k].get("hit10")]
    lost = [k for k in bi if k in ni and bi[k].get("hit10") and not ni[k].get("hit10")]
    print(f"\nflip: gained={len(gained)} {gained}")
    print(f"      lost  ={len(lost)} {lost}")
    if lost:
        print("  [WARN] 每一条 lost 都必须在报告里给原因，否则算未完成")

    # gates
    failed = []
    for expr in args.gate:
        name, op, thr = parse_gate(expr)
        b, n = metric_of(base, name), metric_of(new, name)
        if b is None or n is None:
            failed.append((expr, "metric missing"))
            print(f"\nGATE {expr:<22} ERROR (metric missing)")
            continue
        ok = OPS[op](n - b, thr) if op in (">=", "<=", ">", "<") and name.startswith(("hit", "ecr")) \
            else OPS[op](n, thr)
        # hit/ecr 类按 Δ 判，其余按绝对值判
        shown = f"delta={n - b:+.1f} vs {thr:+.1f}" if name.startswith(("hit", "ecr")) else f"value={n} vs {thr}"
        print(f"\nGATE {expr:<22} {'PASS' if ok else 'FAIL'}  ({shown})")
        if not ok:
            failed.append((expr, shown))

    if failed:
        print(f"\n[FAIL] {len(failed)} 个 gate 不通过：{[f[0] for f in failed]}")
        return 1
    print("\n[PASS] 全部 gate 通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
"""评测统一约定。所有 eval 脚本的 tender_id 都从这里取。

Wilson 95% 置信区间（不引入 scipy，纯 math 实现）。
为什么必须带 CI
---------------
74 chunk / 70 题的库里，hit@5 一个名次翻转就是 ±1.4pp，"72.9 vs 75.7"
这种差异完全落在抽样噪声里。只报点估计会让人把噪声当改进。
CI 用 Wilson score interval 而非正态近似（Wald）：小样本 + 接近 0/1 时
Wald 会给出越界或过窄的区间（10/27 的 Wald 下界会掉到 14.4%，实际 21.5%）。
"""

import math

TEST_TENDER_ID = 1

Z95 = 1.959963984540054  # 标准正态双侧 95% 分位


def wilson95(k, n):
    """二项比例 k/n 的 Wilson 95% 置信区间，返回 [下限, 上限]（百分数，1 位小数）。

    k=命中数, n=样本数。n<=0 返回 [None, None]，避免除零把整份评测带崩。
    """
    if not n or n <= 0:
        return [None, None]
    p = float(k) / n
    z2 = Z95 * Z95
    denom = 1.0 + z2 / n
    center = (p + z2 / (2.0 * n)) / denom
    half = (Z95 / denom) * math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n))
    lo = max(0.0, center - half)
    hi = min(1.0, center + half)
    return [round(100.0 * lo, 1), round(100.0 * hi, 1)]

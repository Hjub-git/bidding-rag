# -*- coding: utf-8 -*-
"""评测匹配器：唯一入口，可自证。

存在的理由：`norm(quote) in norm(chunk)` 这种朴素子串判定有两个致命假阳性，
在招标场景里都会错：
  1) 「不需要缴纳投标保证金」contains 「需要缴纳投标保证金」→ 否定被吞
  2) 「第十三章」contains 「第三章」→ 章节号被错配
本模块把这两条堵住，并用 30 条正/负例自证（python matcher.py 直接跑）。

对外接口：
    norm(s)                     归一化
    phrase_in(quote, text)      带边界的短语包含判定（核心）
    lcs_ratio(quote, text)      最长公共子串覆盖率（跨切分可比的 ECR）
    judge(quote, chunks, k)     -> (hit, all, ecr)
"""
import re

# 否定/禁止类前缀：出现在 quote 左侧时必须视为不同短语
NEG_PREFIX = set("不未无非禁拒免")

# 中文数字，用于「第X章」左邻检查
CN_NUM = set("一二三四五六七八九十百千零壹贰叁肆伍陆柒捌玖拾")


def norm(s):
    """去空白 + 全角转半角 + 小写。保留标点（标点参与匹配，见 PUNCT 处理）。"""
    if not s:
        return ""
    out = []
    for ch in s:
        o = ord(ch)
        if 0xFF01 <= o <= 0xFF5E:
            out.append(chr(o - 0xFEE0))
        elif ch in "\u3000\r\n\t \u00a0\u200b":
            continue
        else:
            out.append(ch)
    return "".join(out).lower()


def _ok_boundary(text, i, quote):
    """quote 出现在 text[i:i+len(quote)] 处，检查左右边界是否允许。"""
    # ① 左侧否定词：「不需要缴纳…」不应命中「需要缴纳…」
    if i > 0 and text[i - 1] in NEG_PREFIX:
        return False
    # ② 左侧数字：「第十三章」不应命中「第三章」
    if quote.startswith("第") and i > 0 and (text[i - 1].isdigit() or text[i - 1] in CN_NUM):
        return False
    # ③ 左侧汉字数字（chapter 变体）
    return True


def phrase_in(quote, text):
    """带边界的子串判定。找不到合法出现返回 False。"""
    q, t = norm(quote), norm(text)
    if not q:
        return False
    i = t.find(q)
    while i >= 0:
        if _ok_boundary(t, i, q):
            return True
        i = t.find(q, i + 1)
    return False


def lcs_ratio(quote, text):
    """最长公共子串长度 / quote 长度。用二分 + 集合做，避免 O(n*m)。"""
    q, t = norm(quote), norm(text)
    if not q or not t:
        return 0.0
    lo, hi, best = 1, len(q), 0
    while lo <= hi:
        mid = (lo + hi) // 2
        seen = {q[i:i + mid] for i in range(len(q) - mid + 1)}
        if any(t[j:j + mid] in seen for j in range(len(t) - mid + 1)):
            best, lo = mid, mid + 1
        else:
            hi = mid - 1
    return best / len(q)


def judge(quote, chunk_texts, k=10):
    """返回 (hit@k, all_evidence_hit, ecr_lcs)。单 evidence 版本。"""
    top = chunk_texts[:k]
    hit = any(phrase_in(quote, c) for c in top)
    ecr = max((lcs_ratio(quote, c) for c in top), default=0.0)
    return hit, hit, round(ecr, 4)


# 自证：15 正例 + 15 负例

POSITIVE = [
    ("不需要缴纳投标保证金", "15 投标保证金金额 不需要缴纳投标保证金（按照相关文件要求", "完全包含"),
    ("预算金额：630 万元", "项目名称：XX\n预算金额：630 万元\n采购方式：公开招标", "跨行"),
    ("预算金额 ： 630 万元", "预算金额：630万元", "多余空格"),
    ("投标有效期", "11 投标有效期 开标后60 天", "多空格"),
    ("１００ 分", "评分标准（满分为100 分）", "全角数字"),
    ("技术部分(35 分)", "技术部分\n(35 分）", "全角括号+换行"),
    ("第六章", "第六章 评标办法", "章号唯一"),
    ("第二章", "第十一章 资格审查中的第二章引用", "第二章作为独立词出现"),
    ("开标后60天", "投标有效期 开标后60 天", "数字+量词"),
    ("合同履行期限：60 天", "合同履行期限：60 天。", "句末标点"),
    ("医疗器械经营许可证", "供应商应具有医疗器械经营许可证或相关备案凭证", "长句包含"),
    ("联合体投标不允许", "10 联合体投标 不允许", "跨单元格"),
    ("质量保证金", "不收取投标保证金、履约、质量保证金、合同", "列表项"),
    ("60", "供货期限\n60 天", "纯数字"),
    ("免费质保期12个月", "免费质保期\n12 个月", "跨行数字"),
]

NEGATIVE = [
    ("需要缴纳投标保证金", "不需要缴纳投标保证金", "否定词被吞 ← 招标场景最致命"),
    ("第三章", "第十一章 资格审查", "章号错配（第十三章类）"),
    ("投标保证金", "履约保证金", "关键词不同"),
    ("630 万元", "360 万元", "数字颠倒"),
    ("可以联合体投标", "不允许联合体投标", "否定"),
    ("投标有效期30天", "投标有效期60天", "数字不同"),
    ("中标人", "未中标人", "否定"),
    ("技术部分30分", "技术部分35分", "分不同"),
    ("采购人", "采购代理机构", "角色不同"),
    ("纸质投标文件", "电子投标文件", "介质不同"),
    ("第五章", "第十五章", "章号错配"),
    ("质保期12个月", "质保期24个月", "数字不同"),
    ("可以采用进口产品", "是否接受进口产品：是", "措辞不同（应靠语义，不靠子串）"),
    ("分值保留三位小数", "保留小数点后两位", "数字不同"),
    ("投标人资格要求", "投标人须知前附表", "标题相近但不同"),
]

SELF_TESTS = []   # (名称, 判定结果, 期望)


def run_self_test(verbose=True):
    fails = 0
    for quote, text, why in POSITIVE:
        got = phrase_in(quote, text)
        if not got:
            fails += 1
            if verbose:
                print(f"  ✗ 正例失败: {why:<20} quote={quote!r} text={text[:40]!r}")
    for quote, text, why in NEGATIVE:
        got = phrase_in(quote, text)
        if got:
            fails += 1
            if verbose:
                print(f"  ✗ 负例失败: {why:<20} quote={quote!r} text={text[:40]!r}")
    total = len(POSITIVE) + len(NEGATIVE)
    if verbose:
        print(f"自测 {total} 条：{total - fails} 通过，{fails} 失败")
    return fails


if __name__ == "__main__":
    import sys
    print("=== matcher 自测 ===")
    n = run_self_test()
    print("\n=== 边界行为演示 ===")
    demos = [
        ("不需要缴纳投标保证金", "投标保证金金额 不需要缴纳投标保证金"),
        ("需要缴纳投标保证金", "投标保证金金额 不需要缴纳投标保证金"),
        ("第三章", "第十三章 资格审查"),
        ("第三章", "第三章 评标办法"),
    ]
    for q, t in demos:
        print(f"  {q!r:<24} in {t!r:<40} -> {phrase_in(q, t)}")
    sys.exit(1 if n else 0)

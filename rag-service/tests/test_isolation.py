# -*- coding: utf-8 -*-
"""RAG 项目隔离的可执行 pytest —— 全部调产品函数，不重写任何检索算法。

为什么要有这个文件（复评 A3 点名的问题）：
    ``eval/c1_real_where.py`` 自己重写了一遍 minmax / RSF / BM25 分片，
    它证明的是"那份脚本写得对"，而不是"产品代码的隔离生效"。本文件反过来：
    **检索相关的断言只调 ``RAGService.retrieve``**（数据只用
    ``RAGService.process_document`` 灌入），隔离实现一旦出错，结果里就会混进
    另一个项目的 ``source``；全文没有一行复刻 where / z-score / RSF 的算法。

覆盖四个隔离点：
   1. 向量腿 ``where`` 过滤   —— ``test_vector_leg_where_filter``
   2. BM25 按项目分片         —— ``test_bm25_shard_a_keyword_only_hits_a`` /
                                 ``test_bm25_shard_never_falls_back_to_global`` /
                                 ``test_bm25_shards_are_built_per_tender``
   3. 缓存键带 tender_id      —— ``test_cache_key_does_not_cross_projects``
   4. vector_sim / fusion_score 字段 —— ``test_vector_sim_and_fusion_score``

硬约束：
   - 临时向量库目录（mkdtemp），绝不碰 rag/vector_data 或 eval/vector_base；
   - 全离线（HF_HUB_OFFLINE / TRANSFORMERS_OFFLINE），清空代理变量；
   - 不 mock 模型：真加载 BAAI/bge-small-zh-v1.5（约 6s），这样测的是线上同一条链路。
"""

import atexit
import gc
import os
import shutil
import sys
import tempfile
import time

import pytest

# ─── ① 必须在 import RAGService 之前完成环境准备 ───────────────────────
# rag_service 模块导入时会 setdefault(HF_ENDPOINT)，并且 HuggingFace 系列库在
# import 阶段就读取 HF_HUB_OFFLINE / 代理变量。顺序错了就等于没设。

_RAG_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _RAG_DIR not in sys.path:
    sys.path.insert(0, _RAG_DIR)

# 离线：不联网下载模型，只用本地 HF 缓存
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
# Config.validate() 只校验 DEEPSEEK_API_KEY 存在；测试不调 LLM，给个占位即可。
# 用 setdefault：若 .env 已提供真 key，不覆盖。
os.environ.setdefault("DEEPSEEK_API_KEY", "sk-offline-isolation-test")
# 代理会让 HF 的离线校验之外再发网络请求，统一清掉（大小写都清）
for _proxy_key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                   "ALL_PROXY", "all_proxy"):
    os.environ.pop(_proxy_key, None)

from app.core.config import Config  # noqa: E402

# 磁盘隔离：临时目录当向量库根，既不影响真实库，也保证每个测试进程从空库开始
_TMP_DB = tempfile.mkdtemp(prefix="rag_isolation_db_")
Config.VECTOR_DB_PATH = _TMP_DB

from app.services.rag_service import RAGService  # noqa: E402


def _cleanup_tmp_db():
    """删除临时向量库。Windows 上 sqlite 句柄可能晚一拍释放，重试几次。"""
    for _ in range(3):
        gc.collect()
        shutil.rmtree(_TMP_DB, ignore_errors=True)
        if not os.path.exists(_TMP_DB):
            return True
        time.sleep(0.1)
    return not os.path.exists(_TMP_DB)


atexit.register(_cleanup_tmp_db)

# ─── ② 测试数据：两个项目，各 2 个 chunk，各有独有关键词 ───────────────
# 独有词刻意选罕见词，保证"只靠关键词"就能把 A / B 分开：
#   鲸落 / 液冷 只出现在 A；涡旋 / 青鸾 只出现在 B。
# 每段正文约 450~550 字（合计 > CHUNK_SIZE=800），会被切成 2 个 chunk
# （见 test_ingest_yields_two_chunks_per_project 的断言）。库容量只有 4 个 chunk，
# 远小于候选池 cand=30，于是"没有 where 过滤"一定会把对方项目捞出来。
TENDER_A, TENDER_B = 9001, 9002
DOC_A, DOC_B = 1, 2
SOURCE_A = "isolation_a_鲸落项目.txt"
SOURCE_B = "isolation_b_涡旋项目.txt"

_A_FILL = (
    "本项目鲸落数据中心采用液冷散热与模块化配电架构，机柜功率密度不低于每柜三十千瓦，"
    "并配套双路市电与柴油发电机，全年可用性不低于百分之九十九点九。"
)
# B 的独有词分成两类，这是刻意的：
#   "涡旋" 在 B 的 2 个 chunk 都出现（df=2）；
#   "青鸾" 只在 B 的第 1 个 chunk 出现（df=1）。
# 原因见 test_bm25_shard_never_falls_back_to_global 的注释：df=1 时全库 idf>0，
# 一旦 BM25 腿回退全局索引，"青鸾" 就会给 B 的 chunk 打出正分，泄漏才看得见。
_B_FILL = (
    "本项目涡旋平台强调边缘节点协同，要求实时链路时延不高于二十毫秒，"
    "并支持灰度发布与流量回滚，全年可用性不低于百分之九十九点九。"
)
_B_FILL_QL = (
    "本项目涡旋平台以青鸾数据服务为核心，要求实时链路时延不高于二十毫秒，"
    "并支持灰度发布与流量回滚，全年可用性不低于百分之九十九点九。"
)

A_TEXT = ("第一章 鲸落项目技术要求\n" + _A_FILL * 7
          + "\n\n第二章 鲸落项目商务要求\n" + _A_FILL * 6)
B_TEXT = ("第一章 涡旋项目技术要求\n" + _B_FILL_QL * 12
          + "\n\n第二章 涡旋项目商务要求\n" + _B_FILL * 10)


class FakeUpload:
    """模拟 FastAPI 的 UploadedFile：产品代码只用 name + getvalue()。"""

    def __init__(self, name, content):
        self.name = name
        self._content = content.encode("utf-8")

    def getvalue(self):
        return self._content


@pytest.fixture(scope="module")
def _tmp_db_dir():
    """模块结束（在 rs 销毁之后）清理临时向量库目录；atexit 是第二道保险。"""
    yield _TMP_DB
    _cleanup_tmp_db()


@pytest.fixture(scope="module")
def rs(_tmp_db_dir):
    """真加载模型 → 空临时库 → 用产品入口灌入 A/B 两个项目。"""
    service = RAGService()
    service.process_document(FakeUpload(SOURCE_A, A_TEXT),
                             doc_id=DOC_A, tender_id=TENDER_A)
    service.process_document(FakeUpload(SOURCE_B, B_TEXT),
                             doc_id=DOC_B, tender_id=TENDER_B)
    yield service
    # 先关掉 chroma 客户端，释放 sqlite 句柄，否则 Windows 上临时目录删不掉
    service._clear_cache()
    _client = getattr(service.vector_db, "_client", None)
    if _client is not None and hasattr(_client, "close"):
        try:
            _client.close()
        except Exception:  # 清理失败不应影响测试结论
            pass
    del service


# ─── 辅助 ──────────────────────────────────────────────────────────────

def _sources(results):
    return [(item.get("source") or "") for item in results]


def _assert_no_source(results, source, ctx):
    leaked = [s for s in _sources(results) if source in s]
    assert not leaked, (
        f"{ctx}：结果里出现了另一个项目的 source {source!r}（{len(leaked)} 条）。"
        f"实际返回 source = {_sources(results)}"
    )


def _assert_has_source(results, source, ctx):
    assert any(source in s for s in _sources(results)), (
        f"{ctx}：结果里没有本项目 {source!r} 的 chunk，"
        f"说明过滤/分片把自家数据也挡掉了。实际 source = {_sources(results)}"
    )


# ─── 0. 先证明测试数据本身成立（否则后面的"无泄漏"是假绿）───────────────

def test_ingest_yields_two_chunks_per_project(rs):
    """每个项目各 2 个 chunk，且 metadata 带上了 tender_id。"""
    for doc_id, tender_id, source, text in (
        (DOC_A, TENDER_A, SOURCE_A, A_TEXT),
        (DOC_B, TENDER_B, SOURCE_B, B_TEXT),
    ):
        info = rs.doc_chunk_map[str(doc_id)]
        assert info["tender_id"] == tender_id
        assert info["name"] == source
        assert len(info["ids"]) == 2, (
            f"doc_id={doc_id} 期望切出 2 个 chunk，实际 {len(info['ids'])} 个；"
            f"正文长度 {len(text)}，请调整 _A_FILL/_B_FILL 的重复次数"
        )
    # 两个项目真的进了同一个库（同一 collection），隔离才是个真问题
    all_ids = rs.vector_db.get()["ids"]
    assert len(all_ids) == 4


# ─── 1. 向量腿 where 过滤 ────────────────────────────────────────────────

def test_vector_leg_where_filter(rs):
    """用只有 A 命中的问题查 A：结果里 0 条 B 的 source。"""
    query = "鲸落数据中心的技术要求是什么"
    results = rs.retrieve(query, tender_id=TENDER_A)
    assert results, "A 项目应至少返回一条 chunk"
    _assert_no_source(results, SOURCE_B, "向量腿 where 过滤")
    _assert_has_source(results, SOURCE_A, "向量腿 where 过滤")

    # 反向再验一次：查 B 不能捞到 A
    results_b = rs.retrieve("涡旋平台的技术要求是什么", tender_id=TENDER_B)
    assert results_b, "B 项目应至少返回一条 chunk"
    _assert_no_source(results_b, SOURCE_A, "向量腿 where 过滤（反向）")
    _assert_has_source(results_b, SOURCE_B, "向量腿 where 过滤（反向）")


# ─── 2. BM25 分片 ────────────────────────────────────────────────────────

def test_bm25_shard_a_keyword_only_hits_a(rs):
    """只用 A 项目独有关键词（鲸落/液冷）检索，命中内容必须全部来自 A。"""
    query = "鲸落 液冷散热 模块化配电"
    results = rs.retrieve(query, tender_id=TENDER_A)
    assert results
    _assert_no_source(results, SOURCE_B, "BM25 分片（A 独有词）")
    _assert_has_source(results, SOURCE_A, "BM25 分片（A 独有词）")
    assert any("鲸落" in item.get("content", "") for item in results), (
        "A 分片里没有任何 chunk 含独有词 '鲸落'，关键词腿可能没在 A 分片上工作"
    )


def test_bm25_shard_never_falls_back_to_global(rs):
    """用只在 B 出现、且 df=1 的词查 A：分片生效时 B 不可能进结果。

    为什么这个词必须满足 df=1：
        BM25Okapi 的 idf ≈ ln((N - df + 0.5) / (df + 0.5))。
        全库 N=4 chunks；"青鸾" 只在 B 的第 1 个 chunk 出现（df=1）→ 全库 idf≈+0.85 > 0。
        如果 BM25 腿回退到全局索引，B 的 chunk 会拿到正 BM25 分并被 RSF 融合进
        结果，泄漏立刻可见；而正常分片（A 分片 N=2、无任何 chunk 含"青鸾"）下
        分数为 0，B 永远进不来。
        （若"青鸾"在 2 个 chunk 都出现，df=2 → 全库 idf=0，回退全局也拿不到正分，
          这条测试就抓不到缺陷了——所以数据必须是 df=1。）
    """
    query = "青鸾数据服务与边缘节点协同"
    results = rs.retrieve(query, tender_id=TENDER_A)
    _assert_no_source(results, SOURCE_B, "BM25 分片回退全局探测")
    # A 有 chunk，向量腿仍应返回 A 的低分 chunk —— 不能让"空结果"掩盖回退问题
    assert results, "A 项目有 chunk，用 B 的词查 A 也应返回 A 的低分 chunk"


def test_bm25_shards_are_built_per_tender(rs):
    """补充证据：直接读产品建好的分片，A 分片只含 A 文本，B 分片只含 B 文本。

    这条不是重写算法，只是核对 ``_build_bm25_index`` 按 tender_id 切的边界是否正确——
    2-chunk 分片下 BM25 的 idf 全为 0/负，光靠 retrieve 的行为无法区分
    "分片正常"和"分片为空但向量腿兜住了"，所以补一条状态核对。
    """
    assert set(rs.bm25_text) == {TENDER_A, TENDER_B}, (
        f"BM25 分片应恰好是 A/B 两个项目，实际 {sorted(rs.bm25_text)}"
    )
    assert rs.bm25_text[TENDER_A] and rs.bm25_text[TENDER_B]
    assert all("鲸落" in t for t in rs.bm25_text[TENDER_A])
    assert all("涡旋" in t for t in rs.bm25_text[TENDER_B])
    assert all("青鸾" not in t for t in rs.bm25_text[TENDER_A])
    assert all("鲸落" not in t for t in rs.bm25_text[TENDER_B])


# ─── 3. 缓存键必须带 tender_id ──────────────────────────────────────────

def test_cache_key_does_not_cross_projects(rs):
    """同一句话先问 A、再问 B，B 的结果不能是 A 的缓存。"""
    # 专门造一句本文件其它测试都没用过的查询，保证这次是"先 A 后 B"的首次调用
    query = "投标文件评分标准与履约保证金退还流程如何约定"
    first = rs.retrieve(query, tender_id=TENDER_A)
    second = rs.retrieve(query, tender_id=TENDER_B)

    assert first, "A 项目应返回结果"
    assert second, "B 项目应返回结果"
    _assert_has_source(first, SOURCE_A, "缓存键（先问 A）")
    _assert_no_source(first, SOURCE_B, "缓存键（先问 A）")

    _assert_no_source(second, SOURCE_A, "缓存键（再问 B，命中了 A 的缓存）")
    _assert_has_source(second, SOURCE_B, "缓存键（再问 B）")


# ─── 4. 返回契约：vector_sim ∈ [0,1]，fusion_score 存在 ─────────────────

def test_vector_sim_and_fusion_score(rs):
    """生产路径的字段契约：vector_sim 是余弦相似度，fusion_score 用于排序。"""
    results = rs.retrieve("鲸落项目的商务要求有哪些", tender_id=TENDER_A, k=5)
    assert results
    for item in results:
        assert "vector_sim" in item, f"缺 vector_sim 字段：{list(item)}"
        assert "fusion_score" in item, f"缺 fusion_score 字段：{list(item)}"
        sim = item["vector_sim"]
        assert isinstance(sim, float), f"vector_sim 类型应为 float，实际 {type(sim)}"
        assert 0.0 <= sim <= 1.0, (
            f"vector_sim={sim} 越界 [0,1]；典型错误是写成 1-dist（=1-sqL2）而不是 1-sqL2/2"
        )
        assert isinstance(item["fusion_score"], float)

"""RAG 核心服务，负责文档解析、向量化存储、混合检索（语义 + BM25 关键词）。"""
import os
import re
import json
import time
import logging
import tempfile
import sqlite3
from collections import OrderedDict

import jieba
from rank_bm25 import BM25Okapi

from app.core.config import Config

# 必须在导入 HuggingFace 相关库之前设置镜像端点
os.environ.setdefault("HF_ENDPOINT", Config.HF_ENDPOINT)

from langchain_chroma import Chroma
from langchain_huggingface import HuggingFaceEmbeddings
from langchain.text_splitter import RecursiveCharacterTextSplitter

from app.parsers.document_parser import extract_blocks, extract_text_from_file

logger = logging.getLogger("rag_qa")

# 章节识别与索引版本。section_no 用于章节链前缀（只进 BM25），
# chunker_version / index_version 用于判定"重灌后旧 chunk 是否需要作废"。
_HEADING_RE = re.compile(r"第[一二三四五六七八九十百千\d]+[章节]")
CHUNKER_VERSION = "c1"
INDEX_VERSION = "rag_v2"


class RAGService:
    """RAG 核心服务：嵌入模型管理、文档处理、混合检索"""

    def __init__(self):
        # 检索模式闸：第一件事就判 v1（在读库/加载模型之前，让"v1 起服务"秒退而不是
        # 先跑几十秒 embedding 再失败）。v1 = 关掉项目隔离，只在显式 RAG_ALLOW_V1=1 时放行。
        Config.assert_retrieval_source_allowed()

        # 校验环境变量（HF_ENDPOINT 已在模块级别设置）
        Config.validate()

        # 初始化嵌入模型
        logger.info("正在加载嵌入模型 %s ...", Config.EMBEDDING_MODEL)
        self.embeddings = HuggingFaceEmbeddings(
            model_name=Config.EMBEDDING_MODEL,
        )
        logger.info("嵌入模型加载完成")

        # 初始化向量数据库；打开即做一次可读性校验，HNSW 坏了就地重建（自愈）
        self.vector_db = self._open_vector_db()
        self._ensure_vector_db_healthy()

        # 初始化文本分割器
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=Config.CHUNK_SIZE,
            chunk_overlap=Config.CHUNK_OVERLAP
        )

        # 文档与块ID映射文件路径
        self._map_file = os.path.join(Config.VECTOR_DB_PATH, "doc_chunk_map.json")

        # 加载已有文档映射
        self.doc_chunk_map = self._load_map()

        # BM25 关键词索引
        # BM25 索引本身不持久化，启动时从 ChromaDB 重建。
        # 全局索引（bm25_index/_bm25_texts/_bm25_metadatas）只服务「基线兼容模式」
        # （tender_id=None，供 eval/ 复现 82.9%（旧 35 题 Hit@10）），生产路径一律走分片。
        self.bm25_index = None        # BM25Okapi 实例（全局，仅基线模式）
        self._bm25_texts = []          # 与 BM25 索引对应的原始文本
        self._bm25_metadatas = []      # 与 BM25 索引对应的元数据

        # 按项目分片：tender_id -> BM25Okapi / texts / metadatas。
        # 现状库里的 chunk 只有 source 元数据、没有 tender_id，所以分片此刻会是空的——
        # 这是当前库的已知状态，重新 ingest 后才会填上。空分片时生产检索返回空，
        # 绝不回退到全局索引（回退 = 跨项目泄漏）。
        self.bm25 = {}
        self.bm25_text = {}
        self.bm25_meta = {}

        self._build_bm25_index()

        # 检索结果缓存（使用 OrderedDict 实现 LRU 淘汰）
        self._retrieve_cache = OrderedDict()

    # 文档-块映射持久化

    def _load_map(self):
        """从磁盘加载文档-块映射；文件损坏时从 sqlite metadata 重建，绝不抛异常。

        非原子写会留下半截 JSON，``json.load`` 直接抛 JSONDecodeError，
        连 ``RAGService()`` 都构造不出来（踩过一次：一次异常写盘就让整个
        服务起不来）。这里降级为"从向量库重建"，最坏才退成空映射。
        """
        if not os.path.exists(self._map_file):
            return {}
        try:
            with open(self._map_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                raise ValueError(f"顶层应为 dict，实际是 {type(data).__name__}")
            return data
        except Exception as exc:
            logger.warning("doc_chunk_map.json 不可用（%s: %s），改从向量库 metadata 重建",
                           type(exc).__name__, exc)
            return self._rebuild_map_from_db()

    def _rebuild_map_from_db(self):
        """从向量库 metadata 重建 doc_chunk_map（json 半截/丢失时的兜底）。"""
        try:
            data = self.vector_db.get(include=["metadatas"])
        except Exception as exc:
            logger.warning("重建 doc_chunk_map 失败（%s: %s），按空映射启动；"
                           "文档列表会为空，重新上传即可恢复", type(exc).__name__, exc)
            return {}
        rows = {}
        for cid, meta in zip(data["ids"], data.get("metadatas") or []):
            meta = meta or {}
            doc_id = meta.get("doc_id")
            key = str(doc_id) if doc_id is not None else (meta.get("source") or cid)
            bucket = rows.setdefault(key, {"info": {
                "ids": [], "size": 0,
                "name": meta.get("source") or key,
                "doc_id": doc_id,
                "tender_id": meta.get("tender_id"),
            }, "items": []})
            bucket["items"].append((meta.get("chunk_index") or 0, cid))
        rebuilt = {}
        for key, bucket in rows.items():
            bucket["items"].sort(key=lambda x: x[0])
            bucket["info"]["ids"] = [cid for _, cid in bucket["items"]]
            rebuilt[key] = bucket["info"]
        logger.warning("已从向量库 metadata 重建 doc_chunk_map：%d 个文档 / %d 个片段",
                       len(rebuilt), len(data["ids"]))
        try:
            self.doc_chunk_map = rebuilt
            self._save_map()      # 顺手把磁盘上的坏文件修好
        except Exception:
            pass
        return rebuilt

    def _save_map(self):
        """原子写文档-块映射：写 .tmp → fsync → os.replace。

        原来是直接覆盖目标文件，写到一半崩溃/强杀就留下半截 JSON，
        下次启动 ``_load_map`` 立刻炸（那次事故就是）。``os.replace`` 在同一分区
        上是原子的，读到的要么是旧版本，要么是新版本。
        """
        os.makedirs(os.path.dirname(self._map_file), exist_ok=True)
        tmp = self._map_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.doc_chunk_map, f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self._map_file)

    # 向量库健康 / HNSW 自愈

    # chromadb 1.5.9 把 HNSW 索引落在 chroma.sqlite3 同级的 <segment-uuid>/ 目录，
    # 文件是 hnswlib 的固定五件套。目录里"只有 index_metadata.pickle、缺后面四个
    # bin"就是坏索引（生产 vector_data.808_bad 实测就是这个状态），此后任何
    # get/query 都抛 `Error loading hnsw index`。
    _HNSW_INDEX_FILES = ("index_metadata.pickle", "data_level0.bin", "header.bin",
                         "length.bin", "link_lists.bin")

    def _open_vector_db(self):
        """按当前配置打开 Chroma —— 唯一构造点，自愈时整体替换客户端。"""
        return Chroma(
            persist_directory=Config.VECTOR_DB_PATH,
            embedding_function=self.embeddings,
        )

    def _vector_db_probe(self):
        """探测向量库真的能读能查；返回 (ok, 错误摘要)。

        三道关都要跑（实测踩过）：
          1. ``count()`` —— 只走 metadata 段，用来发现"索引彻底读不出来"；
          2. ``get(limit=1)`` —— 会构造 hnsw segment reader，生产报错从这里出来；
          3. 一次极小 ``query`` —— 必须做：索引被"重建"成残缺状态时，
             count 和 get 都正常（count=882、get 全部命中），只有 query 才抛
             ``Error finding id``。少这一关就是假绿。
        """
        try:
            n = self.vector_db._collection.count()
        except Exception as exc:
            return False, f"{type(exc).__name__}: {exc}"
        try:
            self.vector_db.get(limit=1)
            if n > 0:
                self.vector_db.similarity_search_with_score("健康检查", k=1)
        except Exception as exc:
            return False, f"{type(exc).__name__}: {exc}"
        return True, ""

    def _wal_is_replayable(self):
        """WAL（embeddings_queue）里是否还留着全部向量 —— 决定"删索引"安不安全。

        chromadb 1.x 只把向量浮点数写进 ``chroma.sqlite3`` 的 ``embeddings_queue``，
        ``embeddings`` 表只有 id→segment 的映射。一旦 compaction 成功，队列会被裁剪
        （max_seq_id 前进、行数清空），此后 HNSW bin 就是向量的唯一副本。
        实测：队列被裁剪后再删 bin 重开，count 仍是 882、get 全通，
        但 query 抛 ``Error finding id`` —— 数据其实已经没了，只是表面绿灯。
        """
        db = os.path.join(Config.VECTOR_DB_PATH, "chroma.sqlite3")
        if not os.path.exists(db):
            return True                     # 空库，没有可丢的数据
        try:
            conn = sqlite3.connect(db, timeout=5)
            try:
                q = conn.execute("SELECT COUNT(*) FROM embeddings_queue").fetchone()[0]
                e = conn.execute("SELECT COUNT(*) FROM embeddings").fetchone()[0]
            finally:
                conn.close()
        except Exception as exc:
            logger.warning("读不到 WAL 状态（%s: %s），按「不可重放」处理",
                           type(exc).__name__, exc)
            return False
        if e <= 0:
            return True
        return q >= e

    def _hnsw_files(self):
        """列出向量库目录下疑似 HNSW 索引文件的绝对路径（只认白名单文件名）。"""
        root = Config.VECTOR_DB_PATH
        found = []
        try:
            entries = os.listdir(root)
        except OSError:
            return found
        for name in entries:
            if name.startswith("_"):
                continue                    # 自己的隔离目录，不碰
            seg_dir = os.path.join(root, name)
            if not os.path.isdir(seg_dir):
                continue
            for fname in self._HNSW_INDEX_FILES:
                fpath = os.path.join(seg_dir, fname)
                if os.path.isfile(fpath):
                    found.append(fpath)
        return found

    def _quarantine_hnsw_files(self):
        """把坏索引文件移到 ``<库>/_hnsw_broken/<时间戳>/`` —— 隔离而不是删除。

        坏文件本身是取证材料（生产 ``vector_data.808_bad`` 就是靠一个残留的
        ``index_metadata.pickle`` 定位到"写盘被中断"的），删掉就没得看了。
        目录名以下划线开头，``_hnsw_files`` 会跳过，不会被二次隔离。
        """
        dest = os.path.join(Config.VECTOR_DB_PATH, "_hnsw_broken",
                            time.strftime("%Y%m%d-%H%M%S"))
        moved = []
        for fpath in self._hnsw_files():
            seg = os.path.basename(os.path.dirname(fpath))
            target = os.path.join(dest, f"{seg}__{os.path.basename(fpath)}")
            try:
                os.makedirs(dest, exist_ok=True)
                os.replace(fpath, target)
                moved.append(target)
            except OSError:
                logger.warning("隔离坏索引文件失败（跳过）：%s", fpath)
        return dest, moved

    def _ensure_vector_db_healthy(self, force=False):
        """确保向量库可读可查；HNSW 索引坏了就隔离索引文件 + 重开，从 sqlite 重建。

        为什么敢重建：向量和 metadata 都在 ``chroma.sqlite3`` 里，HNSW 只是
        可从 WAL 重放的派生索引。但重放的前提是 WAL 还没被裁剪，所以删之前
        必须过 ``_wal_is_replayable()`` —— 过不了就拒绝重建并明确报错，
        宁可让运维重新 ingest，也不做"count 正常但 query 全错"的销毁式自愈。

        Args:
            force: True 时跳过探测直接重建（调用方已经撞过 HNSW 异常时用）。
        """
        if not force:
            ok, _err = self._vector_db_probe()
            if ok:
                return
        else:
            _err = "调用方已遇到 HNSW 异常"
        logger.warning("检测到向量库不可读（%s），判定 HNSW 索引损坏", _err)
        if not self._wal_is_replayable():
            raise RuntimeError(
                "HNSW 索引损坏，且向量已无法从 chroma.sqlite3 重放"
                "（embeddings_queue 被 compaction 裁剪过），重建会丢数据，"
                f"已拒绝自动重建；请重新 ingest 受影响的文档。原始报错：{_err}")
        try:
            self.vector_db._client.close()
        except Exception:
            pass                      # 关不掉不影响"隔离文件 + 换客户端"
        dest, moved = self._quarantine_hnsw_files()
        self.vector_db = self._open_vector_db()
        ok, err2 = self._vector_db_probe()
        logger.warning("HNSW 索引已从 sqlite 重建（原因：%s）；"
                       "隔离 %d 个坏索引文件到 %s；重建后可用=%s",
                       _err, len(moved), dest, ok)
        if not ok:
            raise RuntimeError(f"向量库自愈失败，重建后仍不可读：{err2}")

    # 缓存管理

    def _clear_cache(self):
        """清空检索缓存"""
        self._retrieve_cache.clear()

    def _cache_get(self, key):
        """从缓存获取并更新 LRU 顺序"""
        if key in self._retrieve_cache:
            self._retrieve_cache.move_to_end(key)
            return self._retrieve_cache[key]
        return None

    def _cache_set(self, key, value):
        """写入缓存，超出上限时淘汰最旧条目"""
        if key in self._retrieve_cache:
            self._retrieve_cache.move_to_end(key)
        self._retrieve_cache[key] = value
        while len(self._retrieve_cache) > Config.RETRIEVE_CACHE_MAX_SIZE:
            self._retrieve_cache.popitem(last=False)

    # BM25 索引管理

    @staticmethod
    def _tokenize(text):
        """中文分词"""
        return list(jieba.cut(text))

    def _build_bm25_index(self):
        """从 ChromaDB 中读取所有已存储的文本，构建 BM25 索引（全局 + 按项目分片）

        HNSW 索引坏掉时先自愈一次再读；仍不行就抛可读的 RuntimeError，
        让 /api/ingest 的错误信息里能看出"是索引坏了"，而不是一句裸 500。
        """
        try:
            data = self.vector_db.get(include=["documents", "metadatas"])
        except Exception as exc:
            logger.error("读取向量库失败（%s: %s），尝试重建 HNSW 索引后重试一次",
                         type(exc).__name__, exc)
            try:
                self._ensure_vector_db_healthy(force=True)
                data = self.vector_db.get(include=["documents", "metadatas"])
            except Exception as exc2:
                raise RuntimeError(
                    "向量库读取失败，HNSW 索引损坏且自愈未成功："
                    f"{type(exc2).__name__}: {exc2}") from exc2
        if data["ids"]:
            self._bm25_texts = data["documents"]
            self._bm25_metadatas = data["metadatas"]
            tokenized = [self._tokenize(text) for text in self._bm25_texts]
            self.bm25_index = BM25Okapi(tokenized)
            logger.info("BM25 索引已构建，共 %d 个片段", len(self._bm25_texts))

            # 按 tender_id 分片
            # 只收 metadata 里真的带 tender_id 的 chunk。没有 tender_id 的老数据
            # 不进入任何项目分片——宁可某项目检索为空，也绝不跨项目串数据。
            shards = {}
            for text, meta in zip(self._bm25_texts, self._bm25_metadatas):
                tid = (meta or {}).get("tender_id")
                if tid is None:
                    continue
                shards.setdefault(tid, ([], []))
                shards[tid][0].append(text)
                shards[tid][1].append(meta)

            self.bm25 = {}
            self.bm25_text = {}
            self.bm25_meta = {}
            for tid, (texts, metas) in shards.items():
                self.bm25[tid] = BM25Okapi([self._tokenize(t) for t in texts])
                self.bm25_text[tid] = texts
                self.bm25_meta[tid] = metas

            if shards:
                logger.info("BM25 分片已构建：%d 个项目（%s）",
                            len(shards), ", ".join(str(k) for k in sorted(shards)))
            else:
                logger.warning(
                    "BM25 无任何 tender_id 分片（现有 chunk 只有 source 元数据）。"
                    "生产检索将返回空，直到重新 ingest 写入 tender_id。")
        else:
            self.bm25_index = None
            self._bm25_texts = []
            self._bm25_metadatas = []
            self.bm25 = {}
            self.bm25_text = {}
            self.bm25_meta = {}
            logger.info("BM25 索引为空（知识库无数据）")

    def _rebuild_bm25(self):
        """文档变更后重建 BM25 索引"""
        self._build_bm25_index()
        self._clear_cache()

    # 混合检索

    def retrieve(self, query, tender_id=None, k=None, boost_keywords=None):
        """混合检索。

        Args:
            tender_id: 项目隔离键。
                - 有值 → 生产路径：向量腿 ``where`` 过滤 + BM25 分片 +
                  relativeScoreFusion，返回结果带 ``vector_sim`` / ``fusion_score``。
                - ``None`` → 基线兼容路径：全局 BM25 + 旧 RRF，保持 ``eval/`` 的
                  82.9%（旧 35 题 Hit@10）基线可复现。生产调用必须传值，传 None 等于关掉项目隔离。
            boost_keywords: 精确模式关键词列表（如 ["第一百章"]），
                           包含这些词的 chunk 在 BM25 阶段的分数将被加权

        Returns:
            list[dict]：每项至少含 ``content`` / ``source``；生产路径额外含
            ``metadata`` / ``vector_sim``（0~1，拒答阈值用）/ ``fusion_score``（仅排序用）。
        """
        if k is None:
            k = Config.TOP_K

        source = Config.RETRIEVAL_SOURCE

        if source == "v1":
            # 回滚档：全局基线（RRF、无项目隔离）。仅用于复现 82.9%（旧 35 题 Hit@10）。
            # 只在首次触发时告警，避免每个 query 刷一条。
            if not getattr(self, "_warned_v1", False):
                logger.warning("[检索] RAG_RETRIEVAL_SOURCE=v1：走全局基线路径，项目隔离不生效")
                self._warned_v1 = True
        elif tender_id is not None:
            return self._retrieve_scoped(query, tender_id, k, boost_keywords)
        else:
            # 没有 tender_id 就没有隔离边界，宁可不检索也不跨项目。
            raise ValueError(
                "tender_id 必填：禁止全库检索。"
                "（复现历史基线请显式设 RAG_RETRIEVAL_SOURCE=v1）")

        # v1 基线路径（RRF、无项目隔离），与改造前逐行一致。
        # 只为复现一件事：eval/vector_base.bak_20261031 上的 Hit@10 = 82.9%。
        cache_key = (query, k, tuple(boost_keywords or []))

        # 检查缓存
        cached = self._cache_get(cache_key)
        if cached is not None:
            logger.debug("[检索] 缓存命中 (查询: %.30s...)", query)
            return cached

        # 1. 语义搜索
        semantic_k = k * 2  # 多取一些用于融合
        semantic_docs = self.vector_db.similarity_search(query, k=semantic_k)

        # 2. BM25 关键词搜索
        bm25_results = []
        if self.bm25_index is not None:
            tokenized_query = self._tokenize(query)
            bm25_scores = self.bm25_index.get_scores(tokenized_query)

            # 精确模式加权：匹配少的直接置顶，多得按比例 boost
            if boost_keywords:
                match_indices = set()
                for kw in boost_keywords:
                    for i, text in enumerate(self._bm25_texts):
                        if kw in text:
                            match_indices.add(i)
                match_count = len(match_indices)
                if match_count > 0 and match_count <= 20:
                    # 匹配少：给予一个不低于最高BM25分的保底分数
                    max_score = max(bm25_scores) if bm25_scores.any() else 1.0
                    boosted = list(bm25_scores)
                    for i in match_indices:
                        boosted[i] = max(boosted[i], max_score)  # 保底=当前最高分
                    bm25_scores = boosted
                    logger.debug("[检索] 精确匹配%d个chunk，保底置顶", match_count)
                elif match_count > 20:
                    boosted = list(bm25_scores)
                    for i in match_indices:
                        boosted[i] = bm25_scores[i] * 1.5
                    bm25_scores = boosted
                    logger.debug("[检索] 精确加权关键词: %s (匹配%d个chunk)", boost_keywords, match_count)

            # 取分数最高的 semantic_k 个
            sorted_indices = sorted(
                range(len(bm25_scores)),
                key=lambda i: bm25_scores[i],
                reverse=True
            )
            for idx in sorted_indices[:semantic_k]:
                if bm25_scores[idx] > 0:
                    bm25_results.append({
                        "content": self._bm25_texts[idx],
                        "source": self._bm25_metadatas[idx].get("source", "未知文档")
                    })

        # 3. RRF 融合
        fused = self._rrf_fusion(semantic_docs, bm25_results, k)

        # 4. 精确匹配置顶 + 邻居扩展
        if boost_keywords:
            # 找到所有匹配chunk的索引
            match_indices = set()
            for kw in boost_keywords:
                for i, text in enumerate(self._bm25_texts):
                    if kw in text:
                        match_indices.add(i)

            if match_indices and len(match_indices) <= 20:
                # 收集匹配chunk的原文
                boost_key_set = set()
                boosted_chunks = []
                for idx in match_indices:
                    text = self._bm25_texts[idx]
                    key = text.strip()[:200]
                    boost_key_set.add(key)
                    boosted_chunks.append({
                        "content": text,
                        "source": self._bm25_metadatas[idx].get("source", "未知文档")
                    })

                # 邻居扩展：从匹配chunk向后，直到下一个章节/条目标题
                import re as _re
                _heading_pat = _re.compile(r"第[一二三四五六七八九十百千\d]+[章节条]")
                neighbor_chunks = []
                if len(match_indices) <= 3:
                    for idx in sorted(match_indices):
                        match_source = self._bm25_metadatas[idx].get("source", "未知文档")
                        match_heading = None
                        m = _heading_pat.search(self._bm25_texts[idx])
                        if m:
                            match_heading = m.group()

                        # 向后扩展，直到下一节标题
                        for offset in range(1, 25):
                            nb_idx = idx + offset
                            if nb_idx >= len(self._bm25_texts):
                                break
                            if nb_idx in match_indices:
                                continue
                            if self._bm25_metadatas[nb_idx].get("source", "未知文档") != match_source:
                                break

                            nb_text = self._bm25_texts[nb_idx]
                            m2 = _heading_pat.search(nb_text)
                            if m2:
                                nb_heading = m2.group()
                                if match_heading and nb_heading != match_heading:
                                    break  # 到下一节了，停

                            neighbor_chunks.append({
                                "content": nb_text,
                                "source": self._bm25_metadatas[nb_idx].get("source", "未知文档")
                            })
                    logger.debug("[检索] 邻居扩展: 匹配%d个chunk，加入%d个邻居",
                                 len(match_indices), len(neighbor_chunks))

                # 先移除 fused 中已有的精确匹配chunk
                fused = [r for r in fused if r["content"].strip()[:200] not in boost_key_set]
                # 移除邻居也在 fused 中的
                nb_key_set = {nc["content"].strip()[:200] for nc in neighbor_chunks}
                fused = [r for r in fused if r["content"].strip()[:200] not in nb_key_set]

                # 精确匹配chunk置顶
                for bc in reversed(boosted_chunks):
                    fused.insert(0, bc)
                # 邻居紧随其后
                for nc in reversed(neighbor_chunks):
                    fused.insert(len(boosted_chunks), nc)

                fused = fused[:k]  # 保持 top-k

        self._cache_set(cache_key, fused)
        return fused

    def _rrf_fusion(self, semantic_docs, bm25_docs, k):
        """Reciprocal Rank Fusion：综合语义和关键词排名。

        这是「基线兼容路径」唯一的融合实现：``tender_id=None`` 时 ``retrieve``
        走它，用来复现 RRF 口径的历史数字（82.9%，旧 35 题 Hit@10）。生产路径
        （``tender_id`` 有值）走 ``_rsf_fusion``，两者不可互换。
        删掉本方法，``eval/vector_base.bak_20261031`` 上那个数就永久无法复现了。
        """
        # 用文档内容的前 200 字符作为去重 key
        def _make_key(content):
            return content.strip()[:200]

        rrf_scores = {}
        content_store = {}

        # 语义搜索结果打分
        for rank, doc in enumerate(semantic_docs, 1):
            key = _make_key(doc.page_content)
            rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (Config.RRF_K + rank)
            if key not in content_store:
                content_store[key] = {
                    "content": doc.page_content,
                    "source": doc.metadata.get("source", "未知文档")
                }

        # BM25 结果打分
        for rank, doc in enumerate(bm25_docs, 1):
            key = _make_key(doc["content"])
            rrf_scores[key] = rrf_scores.get(key, 0) + 1.0 / (Config.RRF_K + rank)
            if key not in content_store:
                content_store[key] = doc

        # 按 RRF 分数从高到低排序，取 Top-K
        sorted_keys = sorted(rrf_scores, key=rrf_scores.get, reverse=True)[:k]

        return [content_store[key] for key in sorted_keys]

    # 项目隔离检索（生产路径）

    def _retrieve_scoped(self, query, tender_id, k, boost_keywords):
        """带项目隔离的混合检索。

        四个隔离点必须一起生效，少一个就等于没有：
          ① 向量腿 ``filter={"tender_id": ...}``（Chroma 在检索内部过滤，不是先取 k 再筛）
          ② BM25 只在本项目分片内打分
          ③ 缓存键带 ``tender_id``（否则同句先 A 后 B 会串）
          ④ 返回 ``vector_sim``，供拒答/审计使用
        """
        cache_key = ("scoped", tender_id, query, k, Config.FUSION_ALPHA,
                     tuple(boost_keywords or []))
        cached = self._cache_get(cache_key)
        if cached is not None:
            logger.debug("[检索] 缓存命中 tender=%s (查询: %.30s...)", tender_id, query)
            return cached

        # 候选池与最终返回数是两个量（候选 30 → 融合取 10）。
        # 之前误把 k 同时当候选池，导致 α 的实测结论搬不过来。
        cand = Config.RETRIEVE_CANDIDATES

        # ① 向量腿：where 过滤在检索内部生效；取不足就接受不足，绝不跨项目补召回
        hits = self.vector_db.similarity_search_with_score(
            query, k=cand, filter={"tender_id": tender_id})

        vector_scored = []
        for doc, dist in hits:
            # collection 是默认 l2 空间，且 bge 输出已是单位向量：
            # sqL2 = 2 - 2cos  →  cos = 1 - sqL2 / 2。
            # 注意：这里不能写 1 - dist（那就是 1 - sqL2，量级全错）。
            vector_sim = 1.0 - float(dist) / 2.0
            vector_scored.append({
                "content": doc.page_content,
                "source": doc.metadata.get("source", "未知文档"),
                "metadata": doc.metadata,
                "vector_sim": vector_sim,
            })

        if not hits:
            logger.warning("tender=%s 无向量命中：项目没有已入库 chunk，或 chunk 缺 tender_id 元数据",
                           tender_id)
            self._cache_set(cache_key, [])
            return []

        # ② BM25 腿：只在分片内打分，不碰全局索引
        bm25_scored = []
        index = self.bm25.get(tender_id)
        if index is not None:
            texts = self.bm25_text[tender_id]
            metas = self.bm25_meta[tender_id]
            bm25_scores = index.get_scores(self._tokenize(query))

            # 精确编号（第X章/第X条）在本分片内加权
            if boost_keywords:
                match_indices = [i for i, t in enumerate(texts)
                                 if any(kw in t for kw in boost_keywords)]
                if 0 < len(match_indices) <= 20:
                    max_score = max(bm25_scores) if len(bm25_scores) else 1.0
                    for i in match_indices:
                        bm25_scores[i] = max(bm25_scores[i], max_score)

            order = sorted(range(len(bm25_scores)),
                           key=lambda i: bm25_scores[i], reverse=True)[:cand]
            for i in order:
                if bm25_scores[i] <= 0:
                    continue
                bm25_scored.append({
                    "content": texts[i],
                    "source": (metas[i] or {}).get("source", "未知文档"),
                    "metadata": metas[i],
                    "bm25_score": float(bm25_scores[i]),
                })

        # ③ relativeScoreFusion 融合（不是 RRF）
        # 在候选池（cand=30）上融合，最后只返回 k（FUSED_K=10）条。
        fused = self._rsf_fusion(vector_scored, bm25_scored, cand)[:k]

        self._cache_set(cache_key, fused)
        return fused

    def _rsf_fusion(self, vector_scored, bm25_scored, k, alpha=None):
        """relativeScoreFusion：两腿各自 z-score 归一化后加权和。

        alpha 控权重：0 = 纯关键词，1 = 纯向量。默认取 Config.FUSION_ALPHA（0.3）。

        为什么不用 RRF：RRF 只用名次 ``Σ 1/(60+rank)``，会把「关键词腿第 1 名 13.0、
        第 2 名 9.9」这个断层压成 0.0164 vs 0.0161（差 0.00026），等于把精确命中的
        优势丢掉；实测比纯 BM25 低 14.2pp。

        为什么用 z-score 而不是 min-max：min-max 会把每腿第 1 名强行拉到 1.0
        （不管它是 13.0 还是 1.0），两腿第 1 名恒等分，平局再由插入顺序决定。
        z-score 保留各自的分差。注意：在当前 35 题集上两者 top-10 结果相同
        （α=0.3 都是 97.1、α=0.5 都是 94.3，见 eval/c0_5_fusion_zscore.json），
        只有 α=0.7 时 z-score 更好（94.3 vs 88.6）——换归一化不是为涨点，
        是为了让"断层"不再被抹平。
        """
        if alpha is None:
            alpha = Config.FUSION_ALPHA

        def _key(item):
            return item["content"].strip()[:200]

        def _zscore(xs):
            if not xs:
                return []
            mu = sum(xs) / len(xs)
            sd = (sum((x - mu) ** 2 for x in xs) / len(xs)) ** 0.5
            if sd < 1e-9:
                return [0.0] * len(xs)
            return [(x - mu) / sd for x in xs]

        scores = {}
        store = {}

        for item, s in zip(vector_scored, _zscore([x["vector_sim"] for x in vector_scored])):
            key = _key(item)
            scores[key] = scores.get(key, 0.0) + alpha * s
            store.setdefault(key, item)

        for item, s in zip(bm25_scored, _zscore([x["bm25_score"] for x in bm25_scored])):
            key = _key(item)
            scores[key] = scores.get(key, 0.0) + (1.0 - alpha) * s
            store.setdefault(key, item)

        ordered = sorted(scores, key=scores.get, reverse=True)[:k]

        out = []
        for key in ordered:
            item = dict(store[key])
            item["fusion_score"] = scores[key]
            out.append(item)
        return out

    # 文档管理

    def process_document(self, uploaded_file, doc_id=None, tender_id=None):
        """处理上传的文档：提取文本、切分、写向量库（metadata 九件套）。

        Args:
            doc_id / tender_id: 项目隔离与文档主键。生产 ``/api/ingest`` 必须传。
                传 None 时退回旧行为（metadata 只有 ``source``），仅供 eval 历史路径。
                这条退路是写入端的兼容——不带 tender_id 的 chunk 检索不到
                （fail-closed），不会造成跨项目泄漏，因此与检索端"必填"的性质不同。

        metadata 九件套：
            tender_id / doc_id / chunk_index / page_start / page_end /
            section_no / type / chunker_version / index_version

        页码来自 ``extract_blocks`` 的逐页解析：PDF 每页一个 block，
        docx 无物理页码（page 为 None），表格 block 的 type 记 ``table``。
        """
        name = uploaded_file.name
        file_content = uploaded_file.getvalue()
        size = len(file_content)
        ext = name.split(".")[-1].lower()

        if ext not in Config.ALLOWED_EXTENSIONS:
            raise ValueError(f"不支持格式：{ext}")

        if size / 1024 / 1024 > Config.MAX_FILE_SIZE_MB:
            raise ValueError(f"文件过大，最大支持{Config.MAX_FILE_SIZE_MB}MB")

        with tempfile.NamedTemporaryFile(delete=False, suffix=f".{ext}") as tmp:
            tmp.write(file_content)
            path = tmp.name

        try:
            blocks = extract_blocks(path, ext)
            if not any(b["text"].strip() for b in blocks):
                raise ValueError("文档无有效文本内容")

            chunks, metadatas = [], []
            section_no = None

            for block in blocks:
                text = block["text"]
                if not text.strip():
                    continue
                # 章节号沿用"最近一次出现的章/节标题"，到下一个标题才换
                m = _HEADING_RE.search(text)
                if m:
                    section_no = m.group()

                for sub in self.text_splitter.split_text(text):
                    meta = {"source": name}
                    if tender_id is not None:
                        meta.update({
                            "tender_id": tender_id,
                            "doc_id": doc_id,
                            "chunk_index": len(chunks),
                            "page_start": block.get("page"),
                            "page_end": block.get("page"),
                            "section_no": section_no or block.get("section_hint"),
                            "type": "table" if block.get("is_table") else "text",
                            "chunker_version": CHUNKER_VERSION,
                            "index_version": INDEX_VERSION,
                        })
                    chunks.append(sub)
                    metadatas.append(meta)

            if not chunks:
                raise ValueError("文档无有效文本内容")

            # 原子性：先写入 ChromaDB，成功后再更新映射
            ids = self._add_batch(chunks, metadatas=metadatas)

            # 主键用 doc_id：文件名做键时，两个项目同名的"招标文件.pdf"会互相覆盖
            map_key = str(doc_id) if doc_id is not None else name
            self.doc_chunk_map[map_key] = {
                "ids": ids,
                "size": size,
                "name": name,
                "doc_id": doc_id,
                "tender_id": tender_id,
            }
            self._save_map()
            logger.info("文档处理完成: %s (doc_id=%s, tender_id=%s), %d 个片段",
                        name, doc_id, tender_id, len(chunks))

        except Exception:
            # 如果添加失败，尝试回滚已写入的向量数据
            if 'ids' in locals() and ids:
                try:
                    self.vector_db.delete(ids=ids)
                except Exception:
                    pass
            raise
        finally:
            if os.path.exists(path):
                os.remove(path)

        # 写完先校验向量库可读（HNSW 坏了就地自愈重建），再重建 BM25。
        # 不再主动 close 强制落盘 —— 非原子写一旦被中断就留下坏索引，
        # 详见 _flush_vector_db 的注释。
        self._flush_vector_db()

        # 文档变更后重建 BM25 索引
        self._rebuild_bm25()

    def _flush_vector_db(self):
        """写完文档后校验向量库可读，不再主动 close。

        实验结论（只信实测）：
          - chromadb 1.5.9 的 HNSW 落盘是非原子的：segment 目录里先写
            ``index_metadata.pickle``，再写 data_level0/header/length/link_lists
            四个 bin。落盘被中断（进程被杀、写盘异常）就只剩 pickle，
            之后每次 get/query 都抛 ``Error loading hnsw index``
            ——生产 ``vector_data.808_bad`` 实测就是这个状态，100% 复现该报错。
          - "add 74 → close() → 重开"在 808/882 条上并未复现损坏，
            所以"734~1615 区间必坏"这个区间假设不成立；真正的问题是
            落盘这一步本身不安全，而不是某个条数区间。
          - 写入时 chroma 已经把数据和向量持久化到 ``chroma.sqlite3``
            （不 close、甚至强杀之后重开，882 条一条不少），
            所以"不 close 会丢数据"是错的。

        原来这里 ``self.vector_db._client.close()`` 强制落盘，等于把
        "非原子写"暴露给强杀窗口，而且 close 的异常被 ``except: pass`` 吞掉，
        坏索引会被直接带进下一次重开 —— 那正是生产 500 的现场。

        现在改为：不 close，只做一次可读性校验；真坏了就地自愈
        （删索引文件 → 从 sqlite 重建）。函数名与调用点保留，避免其它引用断掉。
        """
        self._ensure_vector_db_healthy()

    def _add_batch(self, texts, metadatas=None, batch=None):
        """批量添加文本到向量数据库"""
        if batch is None:
            batch = Config.BATCH_SIZE
        all_ids = []
        for i in range(0, len(texts), batch):
            batch_texts = texts[i:i + batch]
            batch_meta = metadatas[i:i + batch] if metadatas else None
            ids = self.vector_db.add_texts(batch_texts, metadatas=batch_meta)
            all_ids.extend(ids)
        return all_ids

    def delete_document(self, doc_key):
        """删除指定文档及其向量数据。

        ``doc_key`` 可以是 doc_id（新口径）或文件名（旧口径）。
        """
        key = str(doc_key)
        if key not in self.doc_chunk_map and doc_key not in self.doc_chunk_map:
            return
        if key not in self.doc_chunk_map:
            key = doc_key

        doc_info = self.doc_chunk_map.get(key, {})
        ids = doc_info.get("ids", [])

        if ids:
            self.vector_db.delete(ids=ids)
            del self.doc_chunk_map[key]
            self._save_map()
            logger.info("文档已删除: %s", doc_info.get("name", key))
            self._rebuild_bm25()

    def clear_knowledge(self):
        """清空全部知识库"""
        all_ids = self.vector_db.get()["ids"]
        if all_ids:
            self.vector_db.delete(ids=all_ids)
        self.doc_chunk_map.clear()
        self._save_map()
        logger.info("知识库已清空")
        self._rebuild_bm25()

    def get_all_documents(self, tender_id=None):
        """获取文档列表。

        ``tender_id`` 非 None 时只返回该项目的文档——T1 之后每个 doc 记录都带
        ``tender_id``，这是修 S1 的另一半（原来这里是全库）。
        """
        out = []
        for key, info in self.doc_chunk_map.items():
            if tender_id is not None and info.get("tender_id") != tender_id:
                continue
            out.append({
                "doc_id": info.get("doc_id"),
                "name": info.get("name", key),
                "size": info.get("size", 0),
                "tender_id": info.get("tender_id"),
            })
        return out

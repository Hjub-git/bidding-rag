"""项目全局配置，包含 API Key、向量库路径、模型参数等。"""
import logging
import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

# 注意：config.py 在 app/core/ 下，所以要往上回退三层才到项目根目录 project2/
BASE_DIR = Path(__file__).resolve().parent.parent.parent


class Config:

    # API 设置
    DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY")

    # 内部服务令牌
    # 本服务只绑回环不等于可信：同机任意进程、SSRF、任何能写 127.0.0.1:8000 的东西
    # 都能绕过 Java 的全部 JWT/项目权限。所以 Java→Python 的每条请求都要带
    # ``X-Internal-Token``，Python 侧除 ``GET /api/health`` 外一律校验。
    #  Java 侧 application.yml 的 ``rag.internal-token`` 与这里的 INTERNAL_TOKEN
    #  读的是同一个环境变量 RAG_INTERNAL_TOKEN，两侧必须逐字符一致；
    #  不一致的唯一表现是"全链路 401"，排查成本很高。
    #  安全修复：不再提供默认值。历史默认串 `rag-internal-dev-token-2026` 是公开的，
    #    知道它就能直连 127.0.0.1:8000 绕过 Java 的全部 JWT/项目权限。
    #    start-all.ps1 会生成 32 字节随机令牌并注入 RAG_INTERNAL_TOKEN（两侧同源）。
    INTERNAL_TOKEN = os.getenv("RAG_INTERNAL_TOKEN", "")

    # HuggingFace 镜像端点
    HF_ENDPOINT = "https://hf-mirror.com"

    # 向量库与文档处理
    VECTOR_DB_PATH = str(BASE_DIR / "vector_data")
    # 数据根目录：Java 上传的原始文件落在它下面，/api/ingest 只接受该前缀内的路径
    # （路径防穿越：不接受 ../、绝对路径逃逸，见 main.py 的 /api/ingest）
    # 必须与 Java 的 rag.upload.data-root 同值：scripts/start-all.ps1 会注入
    # RAG_DATA_ROOT=<仓库根>/rag-service/data（绝对路径）给两侧，这里优先读它；
    # 不经 start-all.ps1 单独手起本服务时退回 BASE_DIR/data，与 yml 默认值等价。
    DATA_ROOT = os.getenv("RAG_DATA_ROOT") or str(BASE_DIR / "data")
    CHUNK_SIZE = 800
    CHUNK_OVERLAP = 200
    TOP_K = 10
    MAX_FILE_SIZE_MB = 50
    ALLOWED_EXTENSIONS = {"pdf", "docx", "pptx", "xlsx", "txt"}

    # embedding 配置
    EMBEDDING_MODEL = "BAAI/bge-small-zh-v1.5"
    BATCH_SIZE = 100  # 向量库批量添加的大小
    RETRIEVE_CACHE_ENABLED = True  # 是否启用检索缓存
    RETRIEVE_CACHE_MAX_SIZE = 100  # 检索缓存最大条目数

    # 检索深度契约：候选 30 → 融合取 10 → 入 prompt 6
    # 别混：retrieve(k=10) 的 k 是「最终返回数」，候选池是 RETRIEVE_CANDIDATES。
    RETRIEVE_CANDIDATES = 30
    FUSED_K = 10
    PROMPT_K = 6

    # 回滚开关：回滚走显式配置，不用默认参数。
    #   v2（默认）→ retrieve 必须传 tender_id，None 直接抛错；
    #   v1        → 走全局基线路径（RRF、无隔离），仅用于复现 82.9%（旧 35 题 Hit@10），
    #               生产环境配 v1 等于关掉项目隔离，启动时会有 warning；
    #   both      → 暂等同 v2（v1+v2 合并还没定义，以后再说）。
    RETRIEVAL_SOURCE = os.getenv("RAG_RETRIEVAL_SOURCE", "v2").strip().lower()

    # 混合检索配置
    SEMANTIC_WEIGHT = 0.5  # 语义搜索权重（0~1），BM25 权重为 1-SEMANTIC_WEIGHT
    RRF_K = 60  # RRF 融合参数，越大两边的排名影响越均匀（仅基线兼容路径使用）
    # relativeScoreFusion 的向量腿权重。0=纯关键词，1=纯向量。
    # 2026-10-31 拍板：暂定 0.3。
    #   依据：原文题 z-score 实测 α=0.3 → 97.1（最优），α=0.5 → 94.3，α=0.7 → 94.3。
    #   口语题只有 min-max 时代的数据（α=0.5 更好），口径不匹配，不能用来定参。
    # 扩到 150 题（含 30 条口语题）后必须重定，在那之前 0.3 是暂定值，
    # 不要写进任何对外材料当结论。
    FUSION_ALPHA = 0.3

    # 低置信标注阈值（注意：不是拒答阈值）。
    # 实测：探针与可答题的 vector_sim 完全重叠，单一阈值分不开，
    # 所以这里只做 UI 提示「仅供参考」，绝不据此拒答。
    # 拒答只用结构信号 hit_count == 0。
    LOW_CONFIDENCE_THRESHOLD = 0.55

    # LLM 参数
    DEEPSEEK_MODEL = "deepseek-chat"
    DECISION_TEMPERATURE = 0.5  # 决策用的温度
    STREAM_TEMPERATURE = 0.5  # 流式生成用的温度
    MAX_TOOL_ITERATIONS = 3  # Agent 最大工具调用轮数

    # 对话历史
    MAX_CHAT_HISTORY_TURNS = 10  # 最多保留的对话轮数

    # 日志
    LOG_LEVEL = "INFO"

    # Prompt 路径
    PROMPT_DIR = str(BASE_DIR / "prompts")
    SYSTEM_PROMPT_FILE = "system_prompt.txt"
    WITH_CONTEXT_FILE = "with_context.txt"
    WITHOUT_CONTEXT_FILE = "without_context.txt"

    # 配置校验
    @classmethod
    def validate(cls):
        missing = []
        if not cls.DEEPSEEK_API_KEY:
            missing.append("DEEPSEEK_API_KEY")
        if missing:
            raise EnvironmentError(f"缺少环境变量：{', '.join(missing)}，请在 .env 文件中配置")

    # v1 启动闸
    # v1 = 全局检索、项目隔离不生效（见 retrieve 的基线兼容路径）。
    # 历史口径只该在离线评测里复现，所以：默认启动即拒，显式 RAG_ALLOW_V1=1 才放行。
    V1_DISABLED_MSG = (
        "v1 模式已禁用：会关闭项目隔离。评测复现请用独立脚本"
        "（确实需要 v1，请显式设 RAG_ALLOW_V1=1，且只用于离线评测）")

    @classmethod
    def assert_retrieval_source_allowed(cls):
        """``RETRIEVAL_SOURCE == "v1"`` 时直接抛 RuntimeError，让进程起不来。

        两处调用：``app/main.py`` 模块级（import 阶段就退出，uvicorn 不会"起得来、
        第一个请求才 500"）与 ``RAGService.__init__``（脚本/测试直接 new 服务时也拦）。
        """
        if cls.RETRIEVAL_SOURCE != "v1":
            return
        if os.getenv("RAG_ALLOW_V1", "").strip() == "1":
            log = logging.getLogger("rag_qa")
            log.warning("=" * 78)
            log.warning("!! RAG_ALLOW_V1=1：v1 基线模式已放行 —— 项目隔离不生效，仅限评测复现")
            log.warning("!! 生产/演示环境请立刻去掉 RAG_ALLOW_V1，改回 RAG_RETRIEVAL_SOURCE=v2")
            log.warning("=" * 78)
            return
        raise RuntimeError(cls.V1_DISABLED_MSG)

    # 内部令牌启动闸（安全修复）
    @classmethod
    def assert_internal_token_configured(cls):
        """``RAG_INTERNAL_TOKEN`` 缺失或过短直接抛错，让进程起不来。

        背景：历史默认值 ``rag-internal-dev-token-2026`` 写在代码与配置里，等于公开；
        知道它就能直连 127.0.0.1:8000 绕过 Java 的 JWT 与项目归属校验。
        ``start-all.ps1`` 会生成随机令牌写入 ``_run/rag-token.txt`` 并注入环境变量。
        """
        if not cls.INTERNAL_TOKEN or len(cls.INTERNAL_TOKEN) < 16:
            raise RuntimeError(
                "RAG_INTERNAL_TOKEN 未配置或过短（<16 字符）：内部服务令牌必须由环境变量注入，"
                "请用 start-all.ps1 启动（会自动生成 _run/rag-token.txt），"
                "或显式设置 RAG_INTERNAL_TOKEN 且与 Java 侧一致")

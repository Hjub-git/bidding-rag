"""日志工具：记录检索与整体执行的脱敏摘要。

脱敏口径是硬约束，不是风格偏好：
``py.log`` 是随交付物一起落到磁盘上的运维日志。旧实现对每一次检索都打印
``查询: <用户问题>`` 和``内容全文: <每个片段正文>``，等于把招标文件正文
（评标办法、投标人名称、报价）原样写进日志文件 —— 一个能读 ``_run\\py.log``
的人不需要任何权限就能读到"本项目"的文档内容。

现在检索日志只保留五个与内容无关的量：

======================  ============================================
字段                    含义
======================  ============================================
``trace_id``            与 Java 侧 ``X-Trace-Id`` 同一个值，用于串联整条链
``命中条数``            本次 retrieve 返回几个片段
``sid``                 片段编号列表（``S1,S2,S3``，与 sources 帧一一对应）
``各片段长度``          每个片段的字符数（不打印内容，只留"多长"）
``耗时``                retrieve 的墙钟耗时
======================  ============================================

任何分支都不允许再写片段正文或 query 原文。改完可自查：
``grep -n "content" app/core/logging_config.py`` 应当只剩"取长度"这一处用法。
"""
import contextvars
import functools
import logging
import time
from typing import Callable

logger = logging.getLogger("rag_qa")

SEPARATOR = "-" * 80

# trace_id 透传
# 检索日志要带 trace_id，但 ``RAGService.retrieve`` 的签名里没有它（不能加：
# 检索逻辑与 eval 脚本都按现在的签名调用，加参数等于动检索层）。
# 所以用 contextvar 做只读旁路：``main.chat`` 从 ``X-Trace-Id`` 头取出后 set，
# 装饰器里 get。
# 默认 "-"：``eval/run_eval.py``、pytest 这些没有 HTTP 上下文的调用方直接调
# retrieve 时不该抛异常，日志里显示 "-" 即可。
current_trace_id: contextvars.ContextVar = contextvars.ContextVar(
    "rag_trace_id", default="-")


# 检索日志装饰器

def log_retrieve(func: Callable) -> Callable:
    """装饰 RAGService.retrieve，记录脱敏后的检索摘要（不含任何正文）。"""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        start = time.time()
        result = func(*args, **kwargs)
        elapsed = time.time() - start

        items = result or []
        # sid 与 sources 帧的编号口径一致：按返回顺序 S1..Sn
        sids = ",".join("S%d" % i for i in range(1, len(items) + 1))
        # 只取长度：len() 之后原字符串不再进入日志
        lens = ",".join(
            str(len((it.get("content") or "").strip())) for it in items)

        logger.info(
            "[检索] trace_id=%s | 命中 %d 条 | sid=%s | 各片段长度=%s | 耗时 %.4fs",
            current_trace_id.get(), len(items), sids or "-", lens or "-", elapsed)
        return result
    return wrapper


# 整体执行日志

def log_execute_stream(func: Callable) -> Callable:
    """装饰 execute_stream，记录整次问答的总耗时（同样不打 query 原文）。"""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        query = args[0] if args else kwargs.get("query", "")
        start = time.time()
        logger.info("========== 开始处理问题: trace_id=%s 问题长度=%d ==========",
                    current_trace_id.get(), len(query or ""))
        gen = func(*args, **kwargs)
        for chunk in gen:
            yield chunk
        logger.info("========== 总耗时: %.4fs (trace_id=%s) ==========",
                    time.time() - start, current_trace_id.get())
    return wrapper


# 批量补丁

def patch_all(rag_service, qa_service):
    """对关键方法应用日志装饰器"""
    # 检索日志
    rag_service.retrieve = log_retrieve(rag_service.retrieve)

    # 整体执行日志
    qa_service.execute_stream = log_execute_stream(qa_service.execute_stream)

    return rag_service, qa_service

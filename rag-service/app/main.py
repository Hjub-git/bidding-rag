"""FastAPI 后端服务，提供聊天流式响应、文档管理等 REST API。"""
import hmac
import json
import logging
import os
import sys
import threading
import time

from fastapi import APIRouter, Depends, FastAPI, UploadFile, File, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel
from typing import List, Optional
from langchain_core.messages import HumanMessage, AIMessage

from app.services.rag_service import RAGService
from app.services.qa_service import QAService
from app.core.config import Config
from app.core.logging_config import patch_all, current_trace_id

# v1 启动即拒：RAGService 是懒加载的，只在 __init__ 里拦的话
# uvicorn 会"起得来、第一个请求才 500"。这里在 import 阶段先判一次，
# 让 `RAG_RETRIEVAL_SOURCE=v1 uvicorn app.main:app` 直接进程退出 + 明确报错。
Config.assert_retrieval_source_allowed()
# 内部服务令牌同样在 import 阶段校验：缺失/过短直接进程退出，避免"裸奔起得来"。
Config.assert_internal_token_configured()

# 配置日志
logging.basicConfig(
    level=getattr(logging, Config.LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S"
)

app = FastAPI(title="RAG智能问答API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 全局异常捕获中间件
@app.middleware("http")
async def exception_middleware(request: Request, call_next):
    try:
        return await call_next(request)
    except HTTPException:
        raise
    except ValueError as e:
        return JSONResponse(status_code=400, content={"detail": str(e)})
    except Exception as e:
        logging.getLogger("rag_qa").exception("未捕获异常: %s", e)
        return JSONResponse(status_code=500, content={"detail": f"服务器内部错误：{str(e)}"})

# 内部服务令牌
# 本服务只绑回环不算"鉴权"：同机任意进程 / SSRF 都能直接打 127.0.0.1:8000，
# 从而绕过 Java 侧的 JWT + 项目归属校验（chat / upload / ingest / delete 全裸）。
# 现在除 `GET /api/health`（健康检查、不碰数据）外，所有业务路由都要求
# `X-Internal-Token`，值来自 Config.INTERNAL_TOKEN（与 Java 的 rag.internal-token 同源）。


def require_internal_token(request: Request) -> None:
    """校验 ``X-Internal-Token``；缺失或不匹配 → 401。"""
    got = request.headers.get("X-Internal-Token", "")
    # compare_digest：避免按字节比较时的时序侧信道（令牌是固定字符串，代价可忽略）
    if not got or not hmac.compare_digest(got, Config.INTERNAL_TOKEN):
        logging.getLogger("rag_qa").warning(
            "拒绝未授权请求：%s %s（X-Internal-Token 缺失或错误）",
            request.method, request.url.path)
        raise HTTPException(status_code=401, detail="缺少或错误的内部服务令牌（X-Internal-Token）")


# 业务路由统一挂这个依赖。新增路由必须挂到 api 上；直接写 @app.get/@app.post
# 就会绕过鉴权——"无 token 必须 401"这条验收就是查这个（见 tests/）。
api = APIRouter(dependencies=[Depends(require_internal_token)])

# 延迟初始化，避免模块导入时加载模型
_rag_service = None
_qa_service = None


def get_rag_service():
    global _rag_service
    if _rag_service is None:
        _rag_service = RAGService()
    return _rag_service


def get_qa_service():
    global _qa_service
    if _qa_service is None:
        rs = get_rag_service()
        qs = QAService(rs)
        _qa_service = qs
        # 应用日志补丁
        patch_all(rs, qs)
    return _qa_service


class FileWrapper:
    def __init__(self, filename, content):
        self.name = filename
        self._content = content

    def getvalue(self):
        return self._content


class ChatRequest(BaseModel):
    query: str
    # 项目隔离键：由 Java 侧 assertCanAsk 校验后注入，Python 只认这个值做 where 过滤。
    # 缺省不是 None 就是"不限"——这里刻意不给默认放行，缺了就 400（见 chat()）。
    tender_id: Optional[int] = None
    chat_history: Optional[List[dict]] = []


@app.get("/api/health")
def health_check():
    return {"status": "ok"}


def _sse(obj):
    """统一 SSE 帧：只用 ``data:`` 通道，不用 ``event:`` 字段（SSE 帧约定）。

    原因：``event:`` 名跨代理、跨网关最容易丢，依赖它等于埋一个只在生产暴露的 bug。
    前端一律按 ``payload["type"]`` 分流。
    """
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


@api.post("/api/chat")
async def chat(request: ChatRequest, req: Request):
    query = request.query.strip()
    if not query:
        raise HTTPException(status_code=400, detail="问题不能为空")
    if request.tender_id is None:
        # fail-closed：没有 tender_id 就没有隔离边界，宁可不答也不跨项目检索
        raise HTTPException(status_code=400, detail="tender_id 不能为空")

    # Java 生成 trace_id → X-Trace-Id 头 → Python 日志，三处要能 grep 到同一行。
    trace_id = req.headers.get("X-Trace-Id", "") or "-"
    logger = logging.getLogger("rag_qa")
    # 写进 contextvar：检索/生成这些下游日志也就能带上同一个 trace_id，
    # 而不必给 retrieve() 加参数（那等于动检索层签名，eval 与 pytest 都按现签名调用）。
    current_trace_id.set(trace_id)
    logger.info("trace_id=%s tender_id=%s event=chat_start", trace_id, request.tender_id)

    # 将前端传来的历史记录转换为 LangChain 消息对象
    history_messages = []
    for msg_dict in request.chat_history:
        role = msg_dict.get("role")
        content = msg_dict.get("content")
        if role == "user":
            history_messages.append(HumanMessage(content=content))
        elif role == "assistant":
            history_messages.append(AIMessage(content=content))

    async def generate():
        yield _sse({"type": "meta", "trace_id": trace_id, "tender_id": request.tender_id})
        try:
            async for ev in get_qa_service().execute_stream_async(
                    query, chat_history=history_messages, tender_id=request.tender_id):
                yield _sse(ev)
            # done 帧由 qa_service 统一发（带 hit_count/refused/answer_len 等 stats），
            # 这里不要再补一个，否则 Java 侧会计两次。
        except Exception as e:
            logger.exception("trace_id=%s 问答失败", trace_id)
            yield _sse({"type": "error", "code": "INTERNAL", "message": str(e)[:200]})

    return StreamingResponse(generate(), media_type="text/event-stream")


@api.get("/api/documents")
def list_documents(tender_id: Optional[int] = None):
    # 必须按项目过滤：全库列表会把"别的项目有哪些文件"直接吐出去，
    # 同时给 /api/ingest 的跨项目注入提供了 doc_id。
    if tender_id is None:
        raise HTTPException(status_code=400, detail="tender_id 不能为空")
    return get_rag_service().get_all_documents(tender_id=tender_id)


@api.post("/api/documents/upload")
def upload_document(file: UploadFile = File(...)):
    ext = file.filename.split(".")[-1].lower()
    if ext not in Config.ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"不支持格式：{ext}")

    file_content = file.file.read()
    size = len(file_content)
    if size / 1024 / 1024 > Config.MAX_FILE_SIZE_MB:
        raise HTTPException(status_code=400, detail=f"文件过大，最大支持{Config.MAX_FILE_SIZE_MB}MB")

    wrapped = FileWrapper(file.filename, file_content)
    try:
        get_rag_service().process_document(wrapped)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"处理失败：{str(e)}")

    return {"message": "上传成功", "filename": file.filename, "size": size}


class IngestRequest(BaseModel):
    # 刻意用 Optional：若写成 `int`，缺字段会被 Pydantic 判 422（"校验失败"），
    # 而契约要的是 400（"参数缺失"）。这里让 Pydantic 放行、在函数里显式拒。
    doc_id: Optional[int] = None
    tender_id: Optional[int] = None
    file_path: str


class LocalFile:
    """把磁盘文件包装成 process_document 需要的形式（有 .name 和 .getvalue()）。"""

    def __init__(self, path):
        self.name = os.path.basename(path)
        with open(path, "rb") as f:
            self._content = f.read()

    def getvalue(self):
        return self._content


@api.post("/api/ingest")
def ingest(request: IngestRequest):
    """按落盘路径入库（Java 上传完文件后调用）。

    - ``tender_id`` / ``doc_id`` 缺失 → 400，不给默认值（默认值 = 静默零隔离）。
    - 路径必须在 ``Config.DATA_ROOT`` 内，挡 ``../`` 与绝对路径逃逸。
    - 幂等键 = ``doc_id``：同一 doc_id 重复 ingest 先删旧 chunk 再灌，
      避免"调用成功但写回失败 → 重放"产生重复数据。
    """
    if request.tender_id is None or request.doc_id is None:
        raise HTTPException(status_code=400, detail="doc_id / tender_id 不能为空")

    root = os.path.abspath(Config.DATA_ROOT)
    path = os.path.abspath(request.file_path)
    if not (path == root or path.startswith(root + os.sep)):
        raise HTTPException(status_code=400, detail="文件路径不在数据根目录内")
    if not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="文件不存在")

    # 路径第一段必须等于 tender_id：否则可拿 A 项目的文件路径 + B 的 tender_id 灌入，
    # 让 B 项目用户问出 A 项目内容（这是一条真实的跨项目注入链）。
    rel = os.path.relpath(path, root)
    first_seg = rel.split(os.sep)[0]
    if first_seg != str(request.tender_id):
        raise HTTPException(status_code=400, detail="文件路径与 tender_id 不匹配")

    rs = get_rag_service()

    # 幂等：先清掉同一 doc_id 的旧 chunk
    key = str(request.doc_id)
    if key in rs.doc_chunk_map:
        rs.delete_document(key)

    try:
        rs.process_document(LocalFile(path), doc_id=request.doc_id, tender_id=request.tender_id)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logging.getLogger("rag_qa").exception("ingest 失败 doc_id=%s", request.doc_id)
        raise HTTPException(status_code=500, detail=f"入库失败：{str(e)}")

    info = rs.doc_chunk_map.get(key, {})
    return {
        "status": "ready",
        "doc_id": request.doc_id,
        "tender_id": request.tender_id,
        "chunks": len(info.get("ids", [])),
    }


@api.delete("/api/documents/{doc_name}")
def delete_document(doc_name: str, tender_id: Optional[int] = None):
    """删除单个文档，必须带 ``tender_id`` 且与文档归属一致。

    旧实现直接用 ``doc_name`` 当键删除，没有任何归属校验：谁拿到 8000 的
    内部令牌（或任何能读 ``_run\\rag-token.txt`` 的本机进程）就能按文件名/文档号
    删掉别的项目的文档 —— 删除是不可逆的，比读越界更狠。

    现在三道闸，顺序不能换（先归属后动作，403 之前绝不发生写）：
      ① ``tender_id`` 缺失 → 400（与 ``GET /api/documents`` 同一口径：
         不用 Pydantic 的 422，契约要的是 400「参数缺失」）；
      ② 文档不存在 → 404；
      ③ ``doc_chunk_map[doc_name]["tender_id"]`` 与请求的 ``tender_id`` 不等 → 400。
         这里只回一句"文档不属于该项目"，不透露它属于哪个项目。

    键口径：``doc_chunk_map`` 现在以 ``doc_id``（字符串）为主键，
    所以 ``doc_name`` 实际上传的是 doc_id；旧的文件名键仍能命中。
    """
    if tender_id is None:
        raise HTTPException(status_code=400, detail="tender_id 不能为空")

    rs = get_rag_service()
    info = rs.doc_chunk_map.get(doc_name)
    if info is None:
        raise HTTPException(status_code=404, detail=f"文档不存在：{doc_name}")

    owner = info.get("tender_id")
    if owner != tender_id:
        logging.getLogger("rag_qa").warning(
            "拒绝跨项目删除：doc=%s 归属 tender=%s，请求 tender=%s（已拒绝，未删任何数据）",
            doc_name, owner, tender_id)
        raise HTTPException(status_code=400, detail="文档不属于该项目")

    rs.delete_document(doc_name)
    return {"message": "删除成功", "filename": doc_name, "tender_id": tender_id}


# 无鉴权的 DELETE /api/documents（清空全库）已删除：
# 它不经过 Java 的 JWT 校验，任何能访问 8000 端口的进程都能一键清库。
# 清库操作应走 Java 侧带权限的接口。


# 优雅关闭
# 为什么要有这个端点：验收/运维收工习惯 `Stop-Process` 强杀，正好撞上 chromadb
# 的 HNSW 落盘窗口（非原子写：先写 index_metadata.pickle，再写四个 bin）。实测
# 强杀把段文件写坏、embeddings_queue 被 compaction 裁到 1 → 无法重放 → 服务起不来。
# 有了它，"停服务"就变成「先校验/flush、再退出」，不再赌杀进程的时机。
#
# 三道安全约束：
#   ① 挂在 `api` router 上 → 必须带 `X-Internal-Token`，缺失/错误一律 401；
#   ② 服务只绑 127.0.0.1，浏览器/外网本来就够不到；
#   ③ `include_in_schema=False` → 不在 /docs 里露出，避免手滑点一下把服务停了。
_SHUTDOWN_EXIT_DELAY = 0.30   # 秒：给 ASGI 把响应写回 socket 的时间


def _schedule_exit(delay=_SHUTDOWN_EXIT_DELAY, code=0):
    """在响应返回后退出进程（不能用 ``sys.exit``：只会在工作线程里抛异常）。"""
    def _run():
        time.sleep(delay)
        # 先把日志 handler flush + 关闭，再 os._exit —— 否则 stderr 重定向到文件时
        # 缓冲里的 `[SHUTDOWN]` 那行可能丢，验收就抓不到证据。
        logging.shutdown()
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.flush()
            except Exception:
                pass
        os._exit(code)

    threading.Thread(target=_run, name="graceful-shutdown", daemon=True).start()


@api.post("/api/shutdown", include_in_schema=False)
def shutdown():
    """优雅关闭：校验/落盘向量库 → 延迟 0.3s 退出进程（exit code 0）。

    - ``RAGService`` 是懒加载单例：从没被请求摸过时是 ``None``，直接退出即可。
    - 向量库校验失败时不退出，返回 500 让运维先处理（否则等于带着坏库重启）。
    """
    logger = logging.getLogger("rag_qa")
    pid = os.getpid()
    rs = _rag_service                      # 只读全局，不触发懒加载

    if rs is None:
        logger.info("[SHUTDOWN] RAGService 未初始化（本次未服务任何请求），无需落盘")
    else:
        # 1) 先探一次可读（顺带把"已坏"的库就地自愈）。失败则不退。
        try:
            rs._ensure_vector_db_healthy()
        except Exception as exc:
            logger.exception("[SHUTDOWN] 向量库校验失败，已取消关闭")
            raise HTTPException(
                status_code=500,
                detail=f"向量库校验失败，已取消关闭：{type(exc).__name__}: {exc}")

        # 2) 主动 close：让 chroma 走正常关闭路径（停 system / flush 索引），
        #    而不是等进程被杀在半路上。异常只记 warning，不阻断退出 ——
        #    向量在 add 时已写进 chroma.sqlite3，close 只是索引收尾。
        try:
            rs.vector_db._client.close()
            logger.info("[SHUTDOWN] 向量库客户端已 close()")
        except Exception as exc:
            logger.warning("[SHUTDOWN] 向量库 close() 异常（忽略）：%s: %s",
                           type(exc).__name__, exc)

        logger.info("[SHUTDOWN] 已优雅关闭向量库")

    _schedule_exit()
    return {"status": "shutting_down", "pid": pid}


# 把所有带 X-Internal-Token 依赖的业务路由挂上来（/api/health 不在此列）
app.include_router(api)


if __name__ == "__main__":
    import uvicorn
    # 只绑回环：本服务不对内网/公网暴露，所有入口都经 Java 代理（SseEmitter）
    uvicorn.run(app, host="127.0.0.1", port=8000)

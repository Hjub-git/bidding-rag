"""精简问答服务：LLM 意图分类 + 混合检索 + 流式生成。"""
import asyncio
import json
import re
import time
import logging
from pathlib import Path

from langchain_deepseek import ChatDeepSeek
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage
from app.core.config import Config
from app.core.logging_config import current_trace_id

logger = logging.getLogger("rag_qa")

# 精确模式提取
_PRECISE_PATTERN = re.compile(r"第[一二三四五六七八九十百千\d]+[章节条]")

# 引用编号 [Sn]
# 只认半角方括号 + 纯数字：模型偶尔会写「[S1]」以外的形态（【S1】、[s1]），
# 那些本来就不是 sources 帧的编号口径，不该算"合法引用"，也不该算越界
# —— 越界只抓"格式对但编号不存在"这一种，误伤最小。
_CITE_PATTERN = re.compile(r"\[S(\d+)\]")


def _check_citations(answer, valid_sids):
    """扫答案正文里的 ``[Sn]``，返回越界的编号列表（如 ``["S9"]``）。

    为什么只记不阻断：
        引用校验的目的是"让编造可见"，不是"让回答失败"。模型偶尔多标一个
        ``[S7]``（只检索到 6 条）属于可接受的瑕疵，前端 sources 抽屉里本来就
        只有 S1..S6，用户能自己看出对不上。若在这里掐掉答案或改写正文，
        等于用"少一次越界"换"一次答非所问"，得不偿失。
        所以：只写 stats.invalid_cite + warning，答案原样输出。

    :param answer: 模型输出的完整正文
    :param valid_sids: 本次 sources 帧里真实存在的 sid 集合（``{"S1", ..., "Sn"}``）
    """
    bad = []
    for num in _CITE_PATTERN.findall(answer or ""):
        sid = "S%d" % int(num)
        # "S01" / "S0" 这类写法 int() 归一后能对上 S1 就算合法，不必另立规则
        if sid not in valid_sids and sid not in bad:
            bad.append(sid)
    # 按编号数字排序，日志里 "S2,S10" 比 "S10,S2" 好读
    return sorted(bad, key=lambda s: int(s[1:]))


def _extract_precise(query):
    """从查询中提取精确编号（第X章、第X条等），用于检索加权"""
    return list(dict.fromkeys(_PRECISE_PATTERN.findall(query)))


# 意图兜底规则（纯本地、零 LLM）
# 只用于 O2 改写层的降级路径：LLM 调用失败 / 返回解析不了时，
# 至少还能给出一个合理的意图，而不是把整条问答打成 500。
_LIST_HINTS = ("哪些文档", "有什么文档", "文档列表", "哪些文件", "什么文件", "都有啥", "存了啥")
_CHAT_HINTS = ("你好", "您好", "hi", "hello", "谢谢", "多谢", "在吗", "你是谁", "你能做什么")


def _rule_intent(query):
    """极简规则意图：list 优先，其次短文寒暄，其余一律 search。"""
    q = query or ""
    if any(h in q for h in _LIST_HINTS):
        return "list"
    # 只在短句里认寒暄，避免「你好，这个项目保证金多少」被误判成闲聊
    if len(q.strip()) <= 8 and any(h in q.lower() for h in _CHAT_HINTS):
        return "chat"
    return "search"


def _first_json_object(text):
    """取出文本中第一个花括号平衡的 JSON 对象，跳过字符串内的括号。

    比 ``re.search(r"\\{.*\\}")`` 稳：LLM 常在 JSON 前后带解释，
    且解释本身也可能带花括号。
    """
    if not text:
        return None
    start = text.find("{")
    while start != -1:
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(text)):
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
        start = text.find("{", start + 1)
    return None


# Prompt 加载
# qa_service.py 在 app/services/ 下，往上回退三层才到项目根目录 project2/prompts/
_PROMPT_FILE = Path(__file__).resolve().parent.parent.parent / "prompts" / "qa_prompts.txt"


def _load_prompts():
    """加载 prompt 模板文件，按 === SECTION === 切分"""
    with open(_PROMPT_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    sections = {}
    current_section = None
    current_lines = []

    for line in content.split("\n"):
        if line.startswith("=== ") and line.endswith(" ==="):
            if current_section:
                sections[current_section] = "\n".join(current_lines).strip()
            current_section = line[4:-4].strip()
            current_lines = []
        else:
            current_lines.append(line)

    if current_section:
        sections[current_section] = "\n".join(current_lines).strip()

    return sections


_PROMPTS = _load_prompts()


class QAService:
    """问答服务：LLM 意图分类 → 对应处理路径"""

    def __init__(self, rag_service):
        self.rag_service = rag_service

        # 流式生成模型
        self.llm_stream = ChatDeepSeek(
            model=Config.DEEPSEEK_MODEL,
            temperature=Config.STREAM_TEMPERATURE,
            api_key=Config.DEEPSEEK_API_KEY,
            streaming=True
        )

        # 轻量分类模型（非流式，temperature=0）
        self.llm_classify = ChatDeepSeek(
            model=Config.DEEPSEEK_MODEL,
            temperature=0,
            api_key=Config.DEEPSEEK_API_KEY,
            streaming=False
        )

    def _classify_intent(self, query):
        """极轻量意图分类：一次 LLM 调用，只回一个词"""
        start = time.time()
        try:
            prompt = _PROMPTS["INTENT_CLASSIFY"].format(query=query)
            response = self.llm_classify.invoke(prompt)
            intent = response.content.strip().lower()
            # 强制校正：只接受合法的三个值
            if intent not in ("chat", "list", "search"):
                intent = "search"  # 默认走检索兜底
        except Exception as e:
            logger.warning("[分类] 失败: %s，默认走检索", e)
            intent = "search"

        logger.info("[分类] 查询: %.30s... → %s (耗时: %.3fs)",
                     query, intent, time.time() - start)
        return intent

    def execute_stream(self, query, chat_history=None, tender_id=None):
        """执行问答流程。tender_id 为项目隔离键，必须由调用方（Java 侧）注入。"""
        # 1. 意图分类
        intent = self._classify_intent(query)
        boost_keywords = _extract_precise(query)

        # chat：闲聊，跳过检索
        if intent == "chat":
            yield from self._stream_chat(query, chat_history, tender_id)
            return

        # list：文档列表（已禁用，见 _stream_doc_list）
        if intent == "list":
            yield from self._stream_doc_list(chat_history, tender_id)
            return

        # search：混合检索 + 生成
        yield from self._stream_search(query, boost_keywords, chat_history, tender_id)

    # 三条处理路径（异步）

    async def execute_stream_async(self, query, chat_history=None, tender_id=None):
        """异步执行问答流程，yield 结构化事件（SSE 协议 v2，SSE 帧约定）。

        事件类型：``meta`` / ``sources`` / ``token`` / ``done`` / ``error``。
        统一走 ``data: {"type": ...}``，不用 ``event:`` 字段——
        因为 ``event:`` 名跨代理、跨网关最容易丢（已知问题）。
        """
        intent, rewritten = await self._classify_and_rewrite_async(query)
        # 编号加权同时看原问题与改写后的问题：改写可能补全/丢掉「第X条」
        boost_keywords = list(dict.fromkeys(
            _extract_precise(query) + _extract_precise(rewritten)))

        if intent == "chat":
            async for ev in self._stream_chat_async(query, chat_history, tender_id):
                yield ev
            return

        if intent == "list":
            async for ev in self._stream_doc_list_async(chat_history, tender_id):
                yield ev
            return

        # 检索用改写后的 query；生成仍用用户原话（见 _stream_search_async）
        async for ev in self._stream_search_async(
                rewritten, boost_keywords, chat_history, tender_id,
                original_query=query):
            yield ev

    async def _classify_and_rewrite_async(self, query):
        """一次 LLM 往返同时产出 ``(intent, rewritten)``（O2 改写层）。

        为什么合并：意图分类与改写都要读同一个问题、都要一份招标领域常识，
        拆两次调用只是多花一倍往返和一半费用，没有任何信息增益。

        失败绝不外抛：任何异常（网络 / 解析 / 额度）都降级为
        ``(_rule_intent(query), query)`` 并记 warning。理由——
        ``eval/o2_rewrite_eval.py`` 用 ``except AttributeError`` 兜底"未实现"，
        若这里漏出异常会被误读成 O2 没做；更重要的是生产上不能因为
        "改写失败"就让整条问答崩掉（改写 硬约束 4）。
        """
        start = time.time()
        intent, rewritten = _rule_intent(query), query
        try:
            self._ensure_classify_loop()
            prompt = _PROMPTS["INTENT_AND_REWRITE"].format(query=query)
            response = await self.llm_classify.ainvoke(prompt)
            intent, rewritten = self._parse_intent_rewrite(response.content, query)
        except Exception as e:  # noqa: BLE001 —— 降级是设计，不是兜底偷懒
            logger.warning("[改写] 失败: %s: %s，降级为原 query", type(e).__name__, e)
            intent, rewritten = _rule_intent(query), query

        logger.info("[改写] 原始: %.80s → 改写: %.80s | intent=%s | 耗时 %.3fs",
                    query, rewritten, intent, time.time() - start)
        return intent, rewritten

    @staticmethod
    def _parse_intent_rewrite(raw, query):
        """解析 LLM 输出为 ``(intent, rewritten)``；不合格直接抛（由上层降级）。

        容错范围：``\\`\\`\\`json`` 围栏、前后解释文字、缺字段、多余字段、
        非字符串。不用 getattr——本路径不能抛 AttributeError。
        """
        text = raw if isinstance(raw, str) else ("" if raw is None else str(raw))
        text = text.strip()
        # 去掉 markdown 代码围栏（```json ... ```）
        if text.startswith("```"):
            text = text.strip("`").strip()
            if text[:4].lower() == "json":
                text = text[4:].strip()

        obj_text = _first_json_object(text)
        if not obj_text:
            raise ValueError("输出里没有 JSON 对象")
        obj = json.loads(obj_text)  # 坏 JSON → JSONDecodeError（ValueError 子类）
        if not isinstance(obj, dict):
            raise ValueError("JSON 不是对象")

        intent = str(obj.get("intent") or "").strip().lower()
        if intent not in ("chat", "list", "search"):
            # 意图不认识时不必整条降级：用规则意图，保留改写结果
            intent = _rule_intent(query)

        # chat / list 不需要改写，原样返回（避免闲聊被塞进招标术语）
        if intent in ("chat", "list"):
            return intent, query

        rw = obj.get("query")
        if not isinstance(rw, str) or not rw.strip():
            raise ValueError("缺少 query 字段或为空")
        rw = rw.strip().strip('"').strip("“”").strip()
        rw = re.sub(r"\s+", " ", rw)
        if not rw:
            raise ValueError("改写结果为空")
        # 病态输出（复述半篇解释）会稀释 BM25，直接判不合格走降级
        if len(rw) > 200:
            raise ValueError("改写结果过长(%d 字)" % len(rw))
        return intent, rw

    def _ensure_classify_loop(self):
        """防守跨事件循环复用 httpx 客户端。

        底层 ``httpx.AsyncClient`` 的连接池绑定在创建时的事件循环上。
        评测脚本 ``o2_rewrite_eval.py`` 对每题都 ``asyncio.run(...)`` 一次，
        也就是每题换一个新 loop；复用同一个客户端会抛
        ``Event loop is closed`` / ``attached to a different loop``。
        这里在 loop 变化时重建轻量分类模型（构造只是包一层 client，开销可忽略）。
        生产是单 loop，等价于只在首次调用触发一次。
        """
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        if getattr(self, "_classify_loop", None) is not loop:
            self._classify_loop = loop
            self.llm_classify = ChatDeepSeek(
                model=Config.DEEPSEEK_MODEL,
                temperature=0,
                api_key=Config.DEEPSEEK_API_KEY,
                streaming=False,
            )

    async def _classify_intent_async(self, query):
        """异步意图分类（薄包装：内部复用改写层的同一次 LLM 往返）。

        保留是因为它可能有外部调用点；真正在用的是
        ``_classify_and_rewrite_async``。
        """
        intent, _rewritten = await self._classify_and_rewrite_async(query)
        return intent

    async def _stream_chat_async(self, query, chat_history, tender_id=None):
        """异步闲聊路径：不检索，LLM 直接回复"""
        t_start = time.time()
        yield {"type": "status", "text": "正在回复..."}
        messages = [SystemMessage(content=self._build_system_prompt(tender_id))]
        if chat_history:
            max_messages = Config.MAX_CHAT_HISTORY_TURNS * 2
            for msg in chat_history[-max_messages:]:
                if isinstance(msg, (HumanMessage, AIMessage)):
                    messages.append(msg)
        messages.append(HumanMessage(content=query))

        answer_len = 0
        async for chunk in self.llm_stream.astream(messages):
            if chunk.content:
                answer_len += len(chunk.content)
                yield {"type": "token", "text": chunk.content}

        yield {"type": "done", "finish_reason": "stop", "stats": {
            "hit_count": 0, "refused": False, "answer_len": answer_len,
            "latency_ms": int((time.time() - t_start) * 1000),
        }}

    async def _stream_doc_list_async(self, chat_history, tender_id=None):
        """文档列表路径：已禁用。

        原先这里返回全库 ``get_all_documents()``，而那个映射是按文件名建的、
        没有项目维度——任何厂商问一句「有哪些文档」，就能拿到别人项目的
        文件名和大小。这是一条无需越权即可触发的真实泄漏。

        现在 chunk 上还没有 ``tender_id``，按项目过滤只会返回空，所以不如
        明确告知不可用。给 chunk 打上 ``tender_id`` 之后，再改成
        「只列当前项目的文档」。
        """
        text = "文档列表功能暂不可用（正在按项目隔离，稍后开放）。"
        yield {"type": "token", "text": text}
        yield {"type": "done", "finish_reason": "stop", "stats": {
            "hit_count": 0, "refused": False, "answer_len": len(text), "latency_ms": 0,
        }}

    async def _stream_search_async(self, query, boost_keywords, chat_history,
                                   tender_id=None, original_query=None):
        """异步检索路径：混合检索 + LLM 生成，输出结构化事件。

        ``query`` 是用于检索的文本（O2 起是改写后的 query）；
        ``original_query`` 是用户原话，只用于生成 prompt 与日志（before/after）。
        生成仍用原话，避免改写带来的措辞偏差影响回答口径。

        拒答判定只用结构信号：本项目命中数 == 0。
        绝不用相似度阈值——探针与可答题的距离完全重叠（实测）。
        """
        t_start = time.time()
        yield {"type": "status", "text": "正在混合检索知识库..."}
        start = time.time()
        results = self.rag_service.retrieve(
            query, tender_id=tender_id, boost_keywords=boost_keywords)
        elapsed = time.time() - start
        ask_query = original_query or query
        if original_query and original_query != query:
            logger.info("[检索] 原始: %.40s | 改写: %.40s | 耗时: %.4fs | 返回 %d 个片段",
                        original_query, query, elapsed, len(results))
        else:
            logger.info("[检索] 查询: %.30s... | 耗时: %.4fs | 返回 %d 个片段",
                        query, elapsed, len(results))

        # sources 帧必须先于 token 帧：前端先拿到编号才能渲染 [S1]。
        # 页码取 metadata.page_start；docx 的该字段是 None（docx 不转 PDF）。
        items = []
        for i, res in enumerate(results, 1):
            md = res.get("metadata") or {}
            items.append({
                "sid": f"S{i}",
                "doc": res.get("source", ""),
                "page": md.get("page_start"),
                "quote": (res.get("content") or "")[:160],
                # score = vector_sim（0~1），供前端展示与低置信标注
                "score": round(float(res.get("vector_sim") or 0.0), 4),
            })
        yield {"type": "sources", "items": items}

        # 低置信标注（不拒答）：top1 的 vector_sim 低于阈值时提示「仅供参考」。
        # 阈值本身分不开探针与可答题（见 Config.LOW_CONFIDENCE_THRESHOLD 注释），
        # 所以它只影响 UI，绝不参与拒答判断。
        top_score = max((it["score"] for it in items), default=0.0)
        low_confidence = top_score < Config.LOW_CONFIDENCE_THRESHOLD

        # 拒答：本项目零命中，直接回明确文案，不调 LLM（避免编造）
        if not results:
            text = "本项目文档未收录该信息，无法回答。"
            logger.info("[拒答] tender=%s 本项目零命中，refused=1", tender_id)
            yield {"type": "token", "text": text}
            yield {"type": "done", "finish_reason": "refused", "stats": {
                "hit_count": 0, "refused": True, "answer_len": len(text),
                "latency_ms": int((time.time() - t_start) * 1000),
            }}
            return

        # context 用 [S1]…[S6] 编号，必须是 prompt 里要求的引用格式。
        # 定界符（<<<DOCUMENT_CONTEXT ... DOCUMENT_CONTEXT>>>）写在 prompts/qa_prompts.txt
        # 的 USER 模板里，不在这里拼：同步/异步两条路径都用同一个模板，
        # 放在模板里就不会出现"改了一处漏了另一处"的注入防御缺口。
        context = "\n\n".join(
            f"[{it['sid']}] {res['content']}" for it, res in zip(items, results)
        )

        messages = [SystemMessage(content=self._build_system_prompt(tender_id))]

        if chat_history:
            max_messages = Config.MAX_CHAT_HISTORY_TURNS * 2
            recent = chat_history[-max_messages:]
            for msg in recent:
                if isinstance(msg, (HumanMessage, AIMessage)):
                    messages.append(msg)

        messages.append(HumanMessage(
            content=_PROMPTS["USER"].format(context=context, query=ask_query)
        ))

        yield {"type": "status", "text": "正在生成回答..."}
        chunk_count = 0
        answer_len = 0
        gen_start = time.time()
        # 引用校验要扫答案正文，所以这里必须留一份完整文本。
        # 只用于本地校验，不落盘、不进日志（脱敏口径）。
        answer_parts = []

        async for chunk in self.llm_stream.astream(messages):
            chunk_count += 1
            if chunk_count == 1:
                logger.info("[生成] 首 Token 耗时: %.4fs", time.time() - gen_start)
            if chunk.content:
                answer_len += len(chunk.content)
                answer_parts.append(chunk.content)
                yield {"type": "token", "text": chunk.content}

        logger.info("[生成] 流式结束，共 %d 个块，总生成耗时: %.4fs",
                     chunk_count, time.time() - gen_start)

        # 引用校验：越界编号只记录、不改答案
        valid_sids = {it["sid"] for it in items}
        invalid_cite = _check_citations("".join(answer_parts), valid_sids)
        if invalid_cite:
            logger.warning(
                "[引用] invalid_cite trace_id=%s tender_id=%s hit=%d 越界引用=%s 合法范围=S1..S%d",
                current_trace_id.get(), tender_id, len(results),
                ",".join(invalid_cite), len(items))

        yield {"type": "done", "finish_reason": "stop", "stats": {
            "hit_count": len(results), "refused": False, "answer_len": answer_len,
            "latency_ms": int((time.time() - t_start) * 1000),
            "top_score": round(top_score, 4), "low_confidence": low_confidence,
            "invalid_cite": invalid_cite,
        }}

    # 三条处理路径（同步，内部测试备用）

    def _stream_chat(self, query, chat_history, tender_id=None):
        """闲聊路径：不检索，LLM 直接回复"""
        yield ">> [STATUS] 正在回复..."
        messages = [SystemMessage(content=self._build_system_prompt(tender_id))]
        if chat_history:
            max_messages = Config.MAX_CHAT_HISTORY_TURNS * 2
            for msg in chat_history[-max_messages:]:
                if isinstance(msg, (HumanMessage, AIMessage)):
                    messages.append(msg)
        messages.append(HumanMessage(content=query))

        for chunk in self.llm_stream.stream(messages):
            if chunk.content:
                yield chunk.content

    def _stream_doc_list(self, chat_history, tender_id=None):
        """文档列表路径（同步版）：已禁用。
        原先返回全库文档清单，没有项目维度，等于把别人项目的文件名吐出去。
        """
        yield "文档列表功能暂不可用（正在按项目隔离，稍后开放）。"
        return

    def _stream_search(self, query, boost_keywords, chat_history, tender_id=None):
        """检索路径：混合检索 + LLM 生成"""
        yield ">> [STATUS] 正在混合检索知识库..."
        start = time.time()
        results = self.rag_service.retrieve(
            query, tender_id=tender_id, boost_keywords=boost_keywords)
        elapsed = time.time() - start
        logger.info("[检索] 查询: %.30s... | 耗时: %.4fs | 返回 %d 个片段",
                     query, elapsed, len(results))

        context = ""
        if results:
            for res in results:
                context += f"【来源：{res['source']}】\n{res['content']}\n\n"

        messages = [SystemMessage(content=self._build_system_prompt(tender_id))]

        if chat_history:
            max_messages = Config.MAX_CHAT_HISTORY_TURNS * 2
            recent = chat_history[-max_messages:]
            for msg in recent:
                if isinstance(msg, (HumanMessage, AIMessage)):
                    messages.append(msg)

        messages.append(HumanMessage(
            content=_PROMPTS["USER"].format(context=context, query=query)
        ))

        yield ">> [STATUS] 正在生成回答..."
        chunk_count = 0
        gen_start = time.time()

        for chunk in self.llm_stream.stream(messages):
            chunk_count += 1
            if chunk_count == 1:
                logger.info("[生成] 首 Token 耗时: %.4fs", time.time() - gen_start)
            if chunk.content:
                yield chunk.content

        logger.info("[生成] 流式结束，共 %d 个块，总生成耗时: %.4fs",
                     chunk_count, time.time() - gen_start)

    def _build_system_prompt(self, tender_id=None):
        """构建系统提示。

        不再注入全库文档清单。
        原先 ``get_all_documents()`` 是全局映射（按文件名分键、没有项目维度），
        而它被拼进每一次问答的 system prompt——任何已登录用户（哪怕只是
        某个项目的厂家）都能从回答里读到别人项目的文件名。

        给 chunk 打上 ``tender_id``、``doc_chunk_map`` 改成按 ``doc_id`` 分键
        之后，这里再改成「只列当前项目的文档」。
        """
        return _PROMPTS["SYSTEM"].format(
            doc_list="（当前项目文档清单已隔离，按需检索后回答。）")

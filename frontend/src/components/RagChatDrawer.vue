<template>
  <el-drawer
    :visible="visible"
    :with-header="false"
    :size="drawerSize"
    direction="rtl"
    custom-class="rag-drawer"
    @close="handleClosed"
  >
    <div class="rag-container">
      <!-- ── 头部 ── -->
      <div class="rag-header">
        <div class="rag-header-main">
          <div class="rag-title">
            <i class="el-icon-chat-dot-round"></i>
            <span>智能问答</span>
          </div>
          <div v-if="tenderLabel" class="rag-subtitle" :title="tenderLabel">{{ tenderLabel }}</div>
        </div>
        <el-button type="text" class="rag-close" title="关闭" @click="handleClosed">
          <i class="el-icon-close"></i>
        </el-button>
      </div>

      <!-- ── 消息区 ── -->
      <div ref="body" class="rag-body">
        <div v-if="!messages.length" class="rag-empty">
          <i class="el-icon-reading rag-empty-icon"></i>
          <p class="rag-empty-title">直接向招标文件提问</p>
          <p class="rag-empty-desc">回答仅基于本项目招标文件内容，超出范围时会如实告知。</p>
          <div class="rag-suggests">
            <span
              v-for="(item, index) in suggestions"
              :key="index"
              class="rag-suggest"
              @click="askSuggestion(item)"
            >{{ item }}</span>
          </div>
        </div>

        <div
          v-for="msg in messages"
          :key="msg.id"
          class="rag-row"
          :class="'rag-row-' + msg.role"
        >
          <div class="rag-bubble" :class="{ 'rag-bubble-error': msg.error }">
            <!-- status 帧只做进度提示，绝不拼进正文 -->
            <div v-if="msg.status && msg.streaming && !msg.content" class="rag-typing">
              {{ msg.status }}<span class="rag-dots"></span>
            </div>
            <template v-else>
              <span v-if="!msg.content && !msg.error" class="rag-text-muted">（未返回内容）</span>
              <!-- 分段渲染：正文按 [S1] 切成 text / cite 两类片段，全程不走 v-html，模型输出不会被当 HTML 执行。
                   下面这行的 v-for 必须写在同一行：.rag-text 是 white-space: pre-wrap，
                   v-for 之间的换行会被当成真实空格渲染出来（"本项目 S1 规定"）。 -->
              <span v-else class="rag-text"><span v-for="(seg, index) in segmentsOf(msg)" :key="index" :class="segmentClass(seg)" @click="onSegmentClick(seg, msg)">{{ seg.type === 'cite' ? seg.sid : seg.text }}</span></span>
            </template>
          </div>

          <!-- 低置信标注：紧跟答案正文，用低饱和淡黄而不是红色（红色专供 error） -->
          <div v-if="shouldWarnLowConfidence(msg)" class="rag-lowconf">
            低置信：检索最高相似度 {{ formatScore(msg.topScore) }}，答案仅供参考
          </div>

          <!-- 来源卡片：sources 帧在第一个 token 之前到达，这里直接列出来，点哪条都能看原文摘录 -->
          <div v-if="msg.sources && msg.sources.length" class="rag-sources">
            <div class="rag-sources-title">参考来源 · {{ msg.sources.length }} 条</div>
            <div
              v-for="src in msg.sources"
              :key="src.sid"
              class="rag-source"
              @click="jumpTo(src.sid, msg)"
            >
              <span class="rag-source-sid">{{ src.sid }}</span>
              <span class="rag-source-doc" :title="src.doc">{{ src.doc || '（未知文档）' }}</span>
              <span v-if="hasPage(src)" class="rag-source-page">第 {{ src.page }} 页</span>
              <span v-else class="rag-source-nopage">该文档无页码</span>
            </div>
          </div>

          <div v-if="msg.error" class="rag-error">
            <span>{{ msg.errorMessage }}</span>
            <span v-if="msg.errorCode" class="rag-error-code">{{ msg.errorCode }}</span>
            <span v-if="msg.traceId" class="rag-error-trace" :title="msg.traceId">trace {{ shortTrace(msg.traceId) }}</span>
          </div>
          <div v-else-if="msg.truncated" class="rag-hint">回答可能未完整结束</div>
        </div>
      </div>

      <!-- ── 输入区 ── -->
      <div class="rag-footer">
        <el-input
          ref="input"
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          :disabled="!tenderId"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.native="onKeydown"
        />
        <div class="rag-actions">
          <span class="rag-tip">内容由 AI 依据招标文件生成，请以原文为准</span>
          <div class="rag-buttons">
            <el-button v-if="streaming" size="mini" @click="abort">停止</el-button>
            <el-button type="primary" size="mini" :disabled="!canSend" @click="send">发送</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 引用详情：后端还没有 PDF 页锚点接口，先只展示 doc + page + quote，不造不存在的接口 -->
    <el-dialog
      :visible.sync="citeDialog.visible"
      title="引用来源"
      width="420px"
      append-to-body
      custom-class="rag-cite-dialog"
    >
      <div class="rag-cite-dialog-body">
        <div class="rag-cite-dialog-row">
          <span class="rag-cite-dialog-label">编号</span>
          <span class="rag-cite-dialog-value rag-cite-dialog-sid">{{ citeDialog.sid }}</span>
        </div>
        <div class="rag-cite-dialog-row">
          <span class="rag-cite-dialog-label">文档</span>
          <span class="rag-cite-dialog-value">{{ citeDialog.doc || '（未知文档）' }}</span>
        </div>
        <div class="rag-cite-dialog-row">
          <span class="rag-cite-dialog-label">页码</span>
          <span v-if="citeDialog.page !== null" class="rag-cite-dialog-value">第 {{ citeDialog.page }} 页</span>
          <span v-else class="rag-cite-dialog-value rag-cite-dialog-nopage">该文档无页码</span>
        </div>
        <div class="rag-cite-dialog-quote">{{ citeDialog.quote || '（无原文摘录）' }}</div>
        <p class="rag-cite-dialog-tip">PDF 原文页定位尚未接入，请以上方摘录文字与招标文件原文核对。</p>
      </div>
    </el-dialog>
  </el-drawer>
</template>

<script>
/**
 * 招标文件智能问答抽屉（SSE 契约 v2）。
 *
 * 为什么用原生 fetch 而不是 services/api.js 里的 axios 实例：
 * axios 的响应拦截器会把 response.data 整体解包，且默认等待整个 body 读完，
 * 浏览器端也拿不到可读的 ReadableStream，流式效果会被直接吃掉。
 * 所以这里手写 fetch + reader，Authorization 头也自己加。
 *
 * 帧协议（SSE 帧约定 硬约束）：**不再使用 event: 字段**，一律 `data: {"type": ...}`，
 * 分流只看 payload.type —— event 名跨代理 / 跨网关最容易丢，依赖它等于埋一个
 * 只在生产暴露的 bug。后端（rag/app/main.py、rag/app/services/qa_service.py）发出的
 * 类型只有这六种：meta / status / sources / token / done / error。
 */
const DONE_FLAG = '[DONE]'

// 正文里的引用标记。split 用捕获组一次切开，天然无状态：
// 模块级带 g 的正则配 exec 时必须手动归零 lastIndex，忘了就会「第二次渲染漏掉开头」。
const CITE_SPLIT = /(\[[Ss]\d+\])/
const CITE_EXACT = /^\[([Ss]\d+)\]$/

export default {
  name: 'RagChatDrawer',
  props: {
    // 抽屉是否可见，父组件用 :visible.sync 绑定
    visible: {
      type: Boolean,
      default: false
    },
    // 招标项目 id，对应后端请求体的 tender_id
    tenderId: {
      type: [Number, String],
      default: null
    },
    // 头部展示的招标项目标识，如「P2025-001 · 数控机床」
    tenderLabel: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      messages: [],
      input: '',
      streaming: false,
      // 用于中止 fetch / 停止读流
      controller: null,
      // 最近一轮的来源，作为 resolveSource 在拿不到 msg 时的兜底（sources 帧写入）
      sources: [],
      uid: 0,
      citeDialog: {
        visible: false,
        sid: '',
        doc: '',
        page: null,
        quote: ''
      },
      viewportWidth: typeof window !== 'undefined' ? window.innerWidth : 1280,
      suggestions: [
        '这个项目的投标截止时间和开标安排是什么？',
        '对投标人的资质有哪些要求？',
        '投标保证金怎么规定的？'
      ]
    }
  },
  computed: {
    drawerSize() {
      // 窄屏铺满，宽屏固定 480px，避免照搬写死的 px 在小屏上撑爆
      return this.viewportWidth < 560 ? '92%' : '480px'
    },
    canSend() {
      return !!this.tenderId && !this.streaming && !!this.input.trim()
    }
  },
  watch: {
    visible(val) {
      if (val) {
        this.$nextTick(() => {
          this.focusInput()
          this.scrollToBottom()
        })
      } else {
        // 抽屉被关掉时必须掐掉流，否则后台会继续生成
        this.abort()
      }
    },
    // 切换到另一个招标项目时，旧对话不能留着，否则会串项目
    tenderId() {
      this.resetConversation()
    }
  },
  mounted() {
    window.addEventListener('resize', this.onResize)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.onResize)
    this.abort()
  },
  methods: {
    onResize() {
      this.viewportWidth = window.innerWidth
    },
    nextId() {
      this.uid += 1
      return this.uid
    },
    focusInput() {
      const inputRef = this.$refs.input
      if (inputRef && typeof inputRef.focus === 'function') {
        inputRef.focus()
      }
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.body
        if (el) {
          el.scrollTop = el.scrollHeight
        }
      })
    },
    handleClosed() {
      this.abort()
      // 幂等：父组件已经把它设成 false 时不再重复 emit，避免回环
      if (this.visible) {
        this.$emit('update:visible', false)
      }
    },
    resetConversation() {
      this.abort()
      this.messages = []
      this.sources = []
      this.input = ''
      this.uid = 0
      this.citeDialog.visible = false
    },
    askSuggestion(text) {
      if (this.streaming) return
      this.input = text
      this.send()
    },
    onKeydown(event) {
      if (event.key !== 'Enter') return
      // Shift+Enter 换行；输入法组字状态下的回车是选词，不能当发送
      if (event.shiftKey || event.isComposing) return
      event.preventDefault()
      this.send()
    },
    abort() {
      if (this.controller) {
        try {
          this.controller.abort()
        } catch (e) {
          // 已结束的 controller 再 abort 无副作用，这里忽略即可
        }
        this.controller = null
      }
      this.streaming = false
    },

    // ── 发送 ──
    async send() {
      const query = this.input.trim()
      if (!query || this.streaming) return
      if (!this.tenderId) {
        this.$message.error('缺少招标项目 ID，无法提问')
        return
      }

      // 历史必须在 push 本轮消息之前取，否则当前问题会在 query 和 chat_history 里重复出现
      const history = this.buildHistory()

      this.messages.push({
        id: this.nextId(),
        role: 'user',
        content: query,
        error: false,
        streaming: false,
        truncated: false,
        status: '',
        traceId: '',
        sources: []
      })
      const assistant = {
        id: this.nextId(),
        role: 'assistant',
        content: '',
        error: false,
        errorMessage: '',
        errorCode: '',
        streaming: true,
        truncated: false,
        // status 帧的进度文案，只用于提示，不进 content
        status: '',
        traceId: '',
        finishReason: '',
        // 低置信标注：done 帧的 stats 带过来。这三个字段必须在创建对象时就声明——
        // Vue 2 检测不到后加的属性，等到 done 帧再 +new prop 是不会触发重渲染的。
        lowConfidence: false,
        topScore: null,
        refused: false,
        sources: []
      }
      this.messages.push(assistant)
      this.input = ''
      this.streaming = true
      this.scrollToBottom()

      const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
      this.controller = controller

      try {
        const token = localStorage.getItem('token')
        // 不要设 Accept: text/event-stream —— 后端权限/参数错误时返回的是 JSON，
        // 带这个头会让 Spring 内容协商失败（HttpMediaTypeNotAcceptable → 500）。
        const headers = {
          'Content-Type': 'application/json'
        }
        if (token) {
          headers.Authorization = 'Bearer ' + token
        }

        const response = await fetch('/api/rag/chat', {
          method: 'POST',
          headers,
          body: JSON.stringify({
            tender_id: Number(this.tenderId),
            query,
            chat_history: history
          }),
          signal: controller ? controller.signal : undefined
        })

        const contentType = (response.headers.get('content-type') || '').toLowerCase()

        // 建连失败时后端返回的是普通 JSON（如 {"code":401,...}），不是 SSE
        if (!response.ok || contentType.indexOf('text/event-stream') === -1) {
          const message = await this.readErrorMessage(response)
          throw new Error(message)
        }

        if (!response.body || typeof response.body.getReader !== 'function') {
          throw new Error('当前浏览器不支持流式读取，请升级浏览器后重试')
        }

        const outcome = await this.consumeStream(response.body.getReader(), assistant)
        // 'done' = 正常结束；'stopped' = error 帧，红色错误行已在 handleFrame 里写好；
        // 'eof' = 连接关了却没收到 done 帧，上游被截断
        if (outcome === 'eof') {
          assistant.truncated = true
        }
      } catch (error) {
        if (error && error.name === 'AbortError') {
          // 用户主动停止：保留已收到的内容，丢掉完全空的消息
          if (!assistant.content) {
            this.messages = this.messages.filter(item => item.id !== assistant.id)
          }
          return
        }
        assistant.error = true
        assistant.errorMessage = (error && error.message) || '请求失败，请稍后重试'
      } finally {
        assistant.streaming = false
        this.streaming = false
        this.controller = null
        this.scrollToBottom()
      }
    },

    // ── 把 SSE 响应体读成帧 ──
    // 返回 'done'（收到终止帧）、'stopped'（收到 error 帧）、'eof'（流自然结束但没终止帧）
    async consumeStream(reader, assistant) {
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let outcome = 'eof'

      try {
        for (;;) {
          const chunk = await reader.read()
          if (chunk.done) break

          buffer += decoder.decode(chunk.value, { stream: true })

          // SSE 允许 CRLF。先归一化；若 chunk 恰好切在 \r\n 中间，
          // 末尾的孤立 \r 留到下一轮再拼，避免把一帧劈成两半。
          buffer = buffer.replace(/\r\n/g, '\n')
          if (!buffer.endsWith('\r')) {
            buffer = buffer.replace(/\r/g, '\n')
          }

          let boundary = buffer.indexOf('\n\n')
          while (boundary !== -1) {
            const rawFrame = buffer.slice(0, boundary)
            buffer = buffer.slice(boundary + 2)
            const frameOutcome = this.handleFrame(rawFrame, assistant)
            if (frameOutcome) {
              outcome = frameOutcome
              break
            }
            boundary = buffer.indexOf('\n\n')
          }

          // done / error 都是终止帧：立刻停止读流。
          // 顺带躲开一个现存的后端小毛病——Java 代理（RagProxyService）只把 [DONE]
          // 当结束标记，而 v2 的 Python 侧改发 {"type":"done"} 不再发 [DONE]，
          // 于是代理会在正常结束后补一条 error 帧「检索服务中断」。
          // done 已经是契约里的正常终点，终止帧之后再来的帧一律不再采纳，
          // 否则每次正常回答都会被误标成失败。
          if (outcome !== 'eof') break
        }

        if (outcome === 'eof') {
          // flush 解码器并处理末尾没有 \n\n 收尾的残帧
          buffer += decoder.decode()
          if (buffer.trim()) {
            const tailOutcome = this.handleFrame(buffer, assistant)
            if (tailOutcome) outcome = tailOutcome
          }
        }
      } finally {
        // 正常读完时 cancel 是空操作；中途停止时靠它主动断开，
        // 否则浏览器会继续挂着这条连接，后端也收不到 onCompletion
        try {
          if (typeof reader.cancel === 'function') {
            reader.cancel()
          }
        } catch (e) {
          // 流已关闭，忽略
        }
        try {
          reader.releaseLock()
        } catch (e) {
          // 锁已被释放或流已关闭，忽略
        }
      }

      return outcome
    },

    /**
     * 解析单个 SSE 帧，**只按 payload.type 分流**。
     * 返回 'done' / 'stopped' 表示流到此为止，返回 '' 表示继续读。
     * 注意：这里不 throw——error 帧要「保留已收到的正文 + 气泡下方红色错误行」，
     * 抛异常会被 send() 的 catch 当成本地请求失败，丢掉 code 和 trace_id。
     */
    handleFrame(rawFrame, assistant) {
      if (!rawFrame || !rawFrame.trim()) return ''

      const lines = rawFrame.split('\n')
      const dataLines = []

      for (let i = 0; i < lines.length; i += 1) {
        const line = lines[i]
        // 空行、以 : 开头的心跳/注释行一律跳过
        if (!line || line.charAt(0) === ':') continue
        // v2 不再用 event: 字段；就算老网关补了一个也不参与分流，这里显式忽略
        if (line.indexOf('event:') === 0) continue
        if (line.indexOf('data:') === 0) {
          let value = line.slice(5)
          if (value.charAt(0) === ' ') value = value.slice(1)
          dataLines.push(value)
        }
        // id: / retry: 等字段本项目用不到，忽略
      }

      if (!dataLines.length) return ''

      const data = dataLines.join('\n')
      // 兼容老后端的纯文本终止符；v2 正常走 {"type":"done"}
      if (data.trim() === DONE_FLAG) return 'done'

      let payload = null
      try {
        payload = JSON.parse(data)
      } catch (e) {
        // 半截 JSON / 非 JSON 帧：宁可丢弃，也不能当正文拼进答案
        return ''
      }
      if (!payload || typeof payload !== 'object') return ''

      switch (payload.type) {
        case 'meta':
          // trace_id 用于和后端日志三段对齐（Java 生成 → X-Trace-Id → Python 日志）
          if (typeof payload.trace_id === 'string') {
            assistant.traceId = payload.trace_id
          }
          // 串项目防护：后端回了别的 tender_id，说明上下文已经不对，立刻中止
          if (payload.tender_id !== undefined && payload.tender_id !== null &&
            String(payload.tender_id) !== String(this.tenderId)) {
            assistant.error = true
            assistant.errorCode = 'TENDER_MISMATCH'
            assistant.errorMessage = '问答上下文与当前招标项目不一致，已中止本次回答'
            return 'stopped'
          }
          return ''

        case 'status':
          // 只做「检索中 / 生成中」的进度提示，不拼进正文
          if (typeof payload.text === 'string') {
            assistant.status = payload.text
          }
          return ''

        case 'sources':
          // 契约：sources 一定在第一个 token 之前，这样 [S1] 才有得可查。
          // 存成数组放在本条消息上（而不是组件级），否则多轮问答里第二轮的 S1
          // 会把第一轮的 [S1] 指到别的文档上。
          assistant.sources = this.normalizeSources(payload.items)
          // 组件级镜像：最近一轮的来源，供 resolveSource 兜底
          this.sources = assistant.sources
          return ''

        case 'token':
          // 正文字段是 text，不是 content
          if (typeof payload.text === 'string' && payload.text) {
            assistant.content += payload.text
            this.scrollToBottom()
          }
          return ''

        case 'done':
          assistant.finishReason = typeof payload.finish_reason === 'string' ? payload.finish_reason : ''
          // stop = 正常说完；length / 其它 = 没有说完
          if (assistant.finishReason && assistant.finishReason !== 'stop') {
            assistant.truncated = true
          }
          // 低置信标注：stats.low_confidence / stats.top_score（P1 新增）。
          // 老后端不带 stats，这里按「无标注」处理，不能让提示一直挂着。
          this.applyLowConfidence(assistant, payload.stats)
          return 'done'

        case 'error':
          assistant.error = true
          assistant.errorCode = typeof payload.code === 'string' ? payload.code : ''
          assistant.errorMessage = payload.message || '检索服务暂时不可用'
          return 'stopped'

        default:
          // 未知类型一律忽略：后端将来加字段/加类型时前端不会崩，也不会污染正文
          return ''
      }
    },

    // ── 低置信标注 ──
    /**
     * 把 done 帧里的 stats 落到消息上。
     * 只做取数，是否展示交给模板的 shouldWarnLowConfidence 判断。
     */
    applyLowConfidence(assistant, stats) {
      if (!stats || typeof stats !== 'object') return
      // 严格判 true：后端可能省略字段，缺省必须是「不标注」而不是「标注」
      assistant.lowConfidence = stats.low_confidence === true
      // 拒答本身已有明确文案，再叠一条低置信提示属于重复告知
      assistant.refused = stats.refused === true
      assistant.topScore = this.normalizeScore(stats.top_score)
    },
    normalizeScore(score) {
      // 0 是合法相似度，不能当缺失值用 falsy 判掉
      if (score === null || score === undefined || score === '') return null
      const num = Number(score)
      return isFinite(num) ? num : null
    },
    // 只在「正常说完 + 低置信 + 非拒答 + 没出错」时才提示
    shouldWarnLowConfidence(msg) {
      if (!msg || msg.role !== 'assistant') return false
      if (!msg.lowConfidence) return false
      if (msg.refused) return false
      if (msg.error) return false
      return msg.finishReason === 'stop'
    },
    formatScore(score) {
      if (typeof score !== 'number' || !isFinite(score)) return '未知'
      // 相似度高精度对用户没有意义，两位小数足够判断「像不像」
      return score.toFixed(2)
    },

    // ── 来源 ──
    normalizeSources(items) {
      if (!Array.isArray(items)) return []
      return items.map((item, index) => {
        const raw = item && typeof item === 'object' ? item : {}
        const sid = typeof raw.sid === 'string' && raw.sid.trim()
          ? raw.sid.trim().toUpperCase()
          : 'S' + (index + 1)
        return {
          sid,
          doc: typeof raw.doc === 'string' ? raw.doc : '',
          page: this.normalizePage(raw.page),
          quote: typeof raw.quote === 'string' ? raw.quote : ''
        }
      })
    },
    // 页码只有两种合法形态：正整数 或 null（docx 不转 PDF，page_start 为 None）
    normalizePage(page) {
      if (page === null || page === undefined || page === '') return null
      const num = Number(page)
      return isFinite(num) && num > 0 ? num : null
    },
    hasPage(source) {
      return !!source && this.normalizePage(source.page) !== null
    },
    resolveSource(sid, msg) {
      if (!sid) return null
      const wanted = String(sid).toUpperCase()
      const list = msg && Array.isArray(msg.sources) ? msg.sources : this.sources
      if (!Array.isArray(list)) return null
      for (let i = 0; i < list.length; i += 1) {
        if (list[i] && String(list[i].sid).toUpperCase() === wanted) return list[i]
      }
      return null
    },

    // ── 正文分段：把 [S1] 切成可点 chip，绝不拼 HTML 字符串 ──
    segmentsOf(msg) {
      const content = msg && typeof msg.content === 'string' ? msg.content : ''
      if (!content) return []
      // 用户气泡原样展示，只有助手答案里的 [S1] 才需要变成可点标记
      if (!msg || msg.role !== 'assistant') return [{ type: 'text', text: content }]

      const parts = content.split(CITE_SPLIT)
      const segments = []
      for (let i = 0; i < parts.length; i += 1) {
        const part = parts[i]
        if (!part) continue
        const matched = CITE_EXACT.exec(part)
        if (!matched) {
          segments.push({ type: 'text', text: part })
        } else {
          const sid = matched[1].toUpperCase()
          segments.push({ type: 'cite', sid, source: this.resolveSource(sid, msg) })
        }
      }
      return segments
    },
    segmentClass(segment) {
      if (!segment || segment.type !== 'cite') return ''
      // 找不到对应来源（比如模型编了 [S9]）时不装成可点 chip，改成灰色虚线
      return segment.source ? 'rag-cite' : 'rag-cite rag-cite-missing'
    },
    onSegmentClick(segment, msg) {
      if (!segment || segment.type !== 'cite') return
      this.jumpTo(segment.sid, msg)
    },
    // 后端还没有 PDF 预览接口，这里只把 doc/page/quote 摆出来，不编造 /api/... 锚点
    jumpTo(sid, msg) {
      const source = this.resolveSource(sid, msg)
      if (!source) {
        this.$message({
          type: 'warning',
          message: '未找到 ' + sid + ' 对应的来源',
          duration: 2000
        })
        return
      }
      this.citeDialog = {
        visible: true,
        sid: source.sid,
        doc: source.doc,
        page: this.normalizePage(source.page),
        quote: source.quote
      }
    },
    shortTrace(traceId) {
      if (!traceId) return ''
      return traceId.length > 8 ? traceId.slice(0, 8) : traceId
    },

    // ── 非 SSE 响应（建连失败）里把错误文案挖出来 ──
    async readErrorMessage(response) {
      let raw = ''
      try {
        raw = await response.text()
      } catch (e) {
        raw = ''
      }

      let message = ''
      if (raw) {
        try {
          const body = JSON.parse(raw)
          message = body.message || body.msg || body.error || ''
        } catch (e) {
          message = raw.slice(0, 200)
        }
      }

      if (!message) {
        if (response.status === 401) {
          message = '登录状态已失效，请重新登录后再试'
        } else if (response.status === 403) {
          message = '没有权限就该招标文件提问'
        } else if (response.status === 404) {
          message = '招标项目不存在'
        } else {
          message = '请求失败（HTTP ' + response.status + '）'
        }
      }
      return message
    },

    buildHistory() {
      const history = []
      this.messages.forEach(msg => {
        // 未完成、出错、被截断的轮次都不进历史，避免把半截答案喂回给模型
        if (msg.streaming || msg.error || !msg.content) return
        history.push({ role: msg.role, content: msg.content })
      })
      return history
    }
  }
}
</script>

<style>
/* 抽屉容器：去掉 Element 默认的 body padding，让内部自己滚 */
.rag-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 引用详情弹窗用了 append-to-body，节点在组件 DOM 树之外，
   scoped 样式（靠 data-v 属性匹配）根本落不到它身上，所以写在这里。 */
.rag-cite-dialog {
  max-width: 420px;
}
.rag-cite-dialog-body {
  font-size: 13px;
  color: #303133;
  line-height: 1.7;
}
.rag-cite-dialog-row {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.rag-cite-dialog-label {
  flex: 0 0 auto;
  width: 32px;
  color: #909399;
}
.rag-cite-dialog-value {
  flex: 1 1 auto;
  word-break: break-all;
}
.rag-cite-dialog-sid {
  color: #409eff;
  font-weight: 600;
}
.rag-cite-dialog-nopage {
  color: #e6a23c;
}
.rag-cite-dialog-quote {
  margin-top: 10px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-left: 3px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow-y: auto;
}
.rag-cite-dialog-tip {
  margin: 10px 0 0;
  font-size: 11px;
  color: #c0c4cc;
  line-height: 1.5;
}
</style>

<style scoped>
.rag-container {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  height: 100%;
  background: #f5f7fa;
}

/* ── 头部 ── */
.rag-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 14px 16px;
  background: #ffffff;
  border-bottom: 1px solid #ebeef5;
  flex: 0 0 auto;
}
.rag-header-main {
  min-width: 0;
}
.rag-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
}
.rag-title i {
  margin-right: 6px;
  color: #409eff;
  font-size: 18px;
}
.rag-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rag-close {
  padding: 4px;
  font-size: 18px;
  color: #909399;
  flex: 0 0 auto;
}
.rag-close:hover {
  color: #409eff;
}

/* ── 消息区 ── */
.rag-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}
.rag-empty {
  text-align: center;
  padding: 32px 8px;
  color: #606266;
}
.rag-empty-icon {
  font-size: 40px;
  color: #c0c4cc;
}
.rag-empty-title {
  margin: 12px 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.rag-empty-desc {
  margin: 0 0 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.rag-suggests {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}
.rag-suggest {
  display: block;
  padding: 8px 12px;
  background: #ffffff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s;
}
.rag-suggest:hover {
  border-color: #409eff;
  color: #409eff;
  background: #ecf5ff;
}

.rag-row {
  display: flex;
  flex-direction: column;
  margin-bottom: 14px;
}
.rag-row-user {
  align-items: flex-end;
}
.rag-row-assistant {
  align-items: flex-start;
}
.rag-bubble {
  max-width: 92%;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.rag-row-user .rag-bubble {
  background: #409eff;
  color: #ffffff;
  border-top-right-radius: 2px;
}
.rag-row-assistant .rag-bubble {
  background: #ffffff;
  color: #303133;
  border: 1px solid #ebeef5;
  border-top-left-radius: 2px;
}
.rag-bubble-error {
  border-color: #fbc4c4 !important;
  background: #fef0f0 !important;
  color: #f56c6c !important;
}
.rag-text {
  /* 纯文本渲染，不使用 v-html，避免模型输出被当成 HTML 执行。
     内部按 [S1] 切成多段 span，pre-wrap 保证换行/缩进原样保留。 */
  white-space: pre-wrap;
}
.rag-text-muted {
  color: #909399;
  font-size: 13px;
}
.rag-typing {
  color: #909399;
  font-size: 13px;
}
.rag-dots::after {
  content: '…';
  animation: rag-blink 1.2s infinite;
}
@keyframes rag-blink {
  0%, 100% { opacity: 0.2; }
  50% { opacity: 1; }
}

/* ── 正文里的 [S1] 引用 chip ── */
.rag-cite {
  display: inline-block;
  margin: 0 1px;
  padding: 0 4px;
  font-size: 12px;
  line-height: 16px;
  color: #409eff;
  background: #ecf5ff;
  border: 1px solid #b3d8ff;
  border-radius: 4px;
  cursor: pointer;
  vertical-align: baseline;
  transition: all 0.2s;
}
.rag-cite:hover {
  background: #409eff;
  border-color: #409eff;
  color: #ffffff;
}
.rag-cite-missing {
  color: #c0c4cc;
  background: #f5f7fa;
  border-style: dashed;
  border-color: #dcdfe6;
  cursor: help;
}
.rag-cite-missing:hover {
  background: #f5f7fa;
  border-color: #c0c4cc;
  color: #909399;
}

/* ── 来源卡片 ── */
.rag-sources {
  margin-top: 8px;
  max-width: 92%;
  padding: 8px 10px;
  background: #ffffff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.rag-sources-title {
  font-size: 12px;
  font-weight: 600;
  color: #909399;
  margin-bottom: 6px;
}
.rag-source {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 2px 0;
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
  cursor: pointer;
}
.rag-source:hover {
  color: #409eff;
}
.rag-source-sid {
  flex: 0 0 auto;
  color: #409eff;
  font-weight: 600;
}
.rag-source-doc {
  flex: 1 1 auto;
  min-width: 0;
  word-break: break-all;
}
.rag-source-page {
  flex: 0 0 auto;
  color: #909399;
}
.rag-source-nopage {
  flex: 0 0 auto;
  color: #e6a23c;
}

/* ── 错误 / 截断提示 ── */
.rag-error {
  margin-top: 6px;
  max-width: 92%;
  font-size: 12px;
  color: #f56c6c;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
}
.rag-error-code {
  color: #f89898;
  font-size: 11px;
}
.rag-error-trace {
  color: #c0c4cc;
  font-size: 11px;
}
.rag-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
}

/* ── 低置信标注（刻意做得不抢眼：淡黄底 + 小字，绝不用红） ── */
.rag-lowconf {
  margin-top: 6px;
  max-width: 92%;
  padding: 4px 8px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #b88230;
}

/* ── 输入区 ── */
.rag-footer {
  flex: 0 0 auto;
  padding: 12px 16px 14px;
  background: #ffffff;
  border-top: 1px solid #ebeef5;
}
.rag-actions {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.rag-tip {
  font-size: 11px;
  color: #c0c4cc;
  line-height: 1.4;
}
.rag-buttons {
  flex: 0 0 auto;
  white-space: nowrap;
}
</style>

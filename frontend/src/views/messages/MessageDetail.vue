<template>
  <div class="message-detail-container">
    <el-card class="header-card" v-if="message">
      <h2>{{ message.title }}</h2>
      <div class="meta">
        <span>作者：{{ message.author }}</span>
        <span>时间：{{ message.created_at }}</span>
      </div>
      <div class="content">{{ message.content }}</div>
    </el-card>

    <el-card class="replies-card">
      <h3>回复</h3>
      <div v-if="replies.length === 0" class="empty">暂无回复</div>
      <el-timeline v-else>
        <el-timeline-item v-for="(r, idx) in replies" :key="r.id" :timestamp="r.created_at">
          <div class="reply-item">
            <div class="head">
              <span class="floor">{{ idx + 1 }}楼</span>
              <span class="author">{{ r.author }}</span>
              <span class="ops" v-if="canDeleteReply(r)">
                <el-button type="text" style="color:#f56c6c;" @click="confirmDeleteReply(r)">删除</el-button>
              </span>
            </div>
            <div class="content">{{ r.content }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <el-card class="reply-form-card" v-if="canReply">
      <h3>发表回复</h3>
      <el-input type="textarea" v-model="replyContent" placeholder="请输入回复内容" />
      <div class="actions">
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submitReply">提交回复</el-button>
        <el-button @click="$router.push('/messages')">返回列表</el-button>
      </div>
    </el-card>
    <div v-else class="hint">
      请登录后回复。如需发布留言，请返回留言板。
    </div>
  </div>
</template>

<script>
import { messageApi } from '@/services/api'
export default {
  name: 'MessageDetail',
  props: ['id'],
  data() {
    return {
      message: null,
      replies: [],
      replyContent: '',
      submitting: false,
    }
  },
  computed: {
    isAuthenticated() { return this.$store.getters.isAuthenticated },
    canReply() { return this.isAuthenticated },
    currentUser() { return this.$store.getters.currentUser },
    userRole() { return this.$store.getters.userRole }
  },
  async mounted() {
    await this.loadDetail()
  },
  methods: {
    async loadDetail() {
      try {
        const data = await messageApi.getMessageDetail(this.$route.params.id || this.id)
        this.message = data.message
        this.replies = Array.isArray(data.replies) ? data.replies : []
      } catch (e) {
        this.$message.error(e.response?.data?.message || '加载留言详情失败')
        console.error('加载留言详情失败', e)
      }
    },
    async submitReply() {
      if (!this.replyContent) {
        this.$message.error('请输入回复内容')
        return
      }
      if (!this.canReply) {
        this.$message.info('请先登录后再回复')
        this.$router.push('/login')
        return
      }
      this.submitting = true
      try {
        await messageApi.replyMessage(this.message.id, { content: this.replyContent })
        this.$message.success('回复已提交')
        this.replyContent = ''
        await this.loadDetail()
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '提交回复失败'
        this.$message.error(msg)
        console.error('提交回复失败', e)
      } finally {
        this.submitting = false
      }
    },
    canDeleteReply(r) {
      const isAuthor = this.currentUser && r.author && this.currentUser.username === r.author
      const isAdmin = this.userRole === 'admin'
      return isAuthor || isAdmin
    },
    async confirmDeleteReply(r) {
      try {
        await this.$confirm(`确认删除该回复？`, '提示', { type: 'warning' })
        await this.deleteReply(r.id)
      } catch (e) {
        if (e !== 'cancel') console.error('回复删除确认失败', e)
      }
    },
    async deleteReply(id) {
      try {
        await messageApi.deleteReply(id)
        this.$message.success('回复已删除')
        await this.loadDetail()
      } catch (e) {
        const msg = e.response?.data?.message || '删除回复失败'
        this.$message.error(msg)
        console.error('删除回复失败', e)
      }
    }
  }
}
</script>

<style scoped>
.message-detail-container { padding: 20px; }
.header-card { margin-bottom: 20px; }
.meta { color: #666; margin-bottom: 10px; display: flex; gap: 20px; }
.content { white-space: pre-wrap; line-height: 1.7; }
.replies-card { margin-bottom: 20px; }
.reply-item .author { font-weight: bold; margin-bottom: 5px; }
.reply-item .head { display:flex; align-items:center; gap:10px; }
.reply-item .ops { margin-left:auto; }
.reply-form-card .actions { margin-top: 10px; display: flex; gap: 10px; }
.empty { color: #909399; }
.hint { color: #909399; padding: 0 0 20px 0; }
</style>
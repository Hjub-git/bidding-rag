<template>
  <div class="message-board-container">
    <el-card class="header-card">
      <div class="title-row">
        <h2>留言板</h2>
        <div class="actions">
          <el-button type="primary" v-if="userRole==='manufacturer'" @click="messageDialogVisible=true">发布留言</el-button>
          <el-button @click="$router.push('/')">返回首页</el-button>
        </div>
      </div>
      <div class="desc">查看公开留言并进入详情，厂家可在此发布留言。</div>
    </el-card>

    <el-card class="list-card">
      <el-table :data="messages" style="width: 100%" @row-click="goDetail">
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column prop="author" label="作者" width="140" />
        <el-table-column prop="created_at" label="时间" width="180" />
        <el-table-column label="操作" width="200">
          <template slot-scope="scope">
            <el-button type="text" @click.stop="goDetail(scope.row)">查看详情</el-button>
            <el-divider direction="vertical"></el-divider>
            <el-button
              v-if="canDelete(scope.row)"
              type="text"
              style="color:#f56c6c;"
              @click.stop="confirmDelete(scope.row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog title="发布留言" :visible.sync="messageDialogVisible" width="40%">
      <el-form :model="messageForm" label-width="100px" @submit.native.prevent>
        <el-form-item label="标题">
          <el-input v-model="messageForm.title" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input type="textarea" v-model="messageForm.content" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submittingMessage" :disabled="submittingMessage" @click="submitMessage">发布</el-button>
          <el-button @click="messageDialogVisible=false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>
  </div>
</template>

<script>
import { messageApi } from '@/services/api'
export default {
  name: 'MessageBoard',
  data() {
    return {
      messages: [],
      messageDialogVisible: false,
      messageForm: { title: '', content: '' },
      submittingMessage: false,
    }
  },
  computed: {
    userRole() {
      return this.$store.getters.userRole
    },
    isLoggedIn() {
      return this.$store.getters.isAuthenticated
    },
    currentUser() {
      return this.$store.getters.currentUser
    }
  },
  async mounted() {
    await this.loadMessages()
  },
  methods: {
    async loadMessages() {
      try {
        const rows = await messageApi.getMessages()
        this.messages = Array.isArray(rows) ? rows : []
      } catch (e) {
        this.$message.error(e.response?.data?.message || '加载留言失败')
        console.error('加载留言失败', e)
      }
    },
    goDetail(row) {
      this.$router.push(`/messages/${row.id}`)
    },
    canDelete(row) {
      const isAuthor = this.currentUser && row.author && this.currentUser.username === row.author
      const isAdmin = this.userRole === 'admin'
      return isAuthor || isAdmin
    },
    async confirmDelete(row) {
      try {
        await this.$confirm(`确认删除留言“${row.title}”？`, '提示', { type: 'warning' })
        await this.deleteMessage(row.id)
      } catch (e) {
        // 用户取消或错误
        if (e !== 'cancel') console.error('删除确认或执行失败', e)
      }
    },
    async deleteMessage(id) {
      try {
        await messageApi.deleteMessage(id)
        this.$message.success('留言已删除')
        await this.loadMessages()
      } catch (e) {
        const msg = e.response?.data?.message || '删除失败'
        this.$message.error(msg)
        console.error('删除留言失败', e)
      }
    },
    async submitMessage() {
      if (!this.isLoggedIn || this.userRole !== 'manufacturer') {
        this.$message.error('请使用厂家账号登录后发布留言')
        return
      }
      if (!this.messageForm.title || !this.messageForm.content) {
        this.$message.error('请填写标题和内容')
        return
      }
      this.submittingMessage = true
      try {
        await messageApi.createMessage({ title: this.messageForm.title, content: this.messageForm.content, is_public: 1 })
        this.$message.success('留言已发布')
        this.messageDialogVisible = false
        this.messageForm = { title: '', content: '' }
        await this.loadMessages()
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '发布留言失败'
        if (e.response?.status === 403) {
          this.$message.error('权限不足：请使用厂家账号登录')
        } else {
          this.$message.error(msg)
        }
        console.error('发布留言失败', e)
      } finally {
        this.submittingMessage = false
      }
    },
  }
}
</script>

<style scoped>
.message-board-container {
  padding: 20px;
}
.header-card {
  margin-bottom: 20px;
}
.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.list-card {
  margin-bottom: 20px;
}
</style>
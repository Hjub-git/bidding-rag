<template>
  <div class="dashboard">
    <el-container>
      <el-header class="dashboard-header">
        <div class="header-content">
          <h2>管理员控制台</h2>
          <div>
            <el-button type="text" @click="$router.push('/')">返回首页</el-button>
            <el-button type="text" @click="logout">退出登录</el-button>
          </div>
        </div>
      </el-header>
      <el-main>
        <el-card class="mb-16">
          <h3>欢迎，{{ currentUser ? currentUser.username : '管理员' }}</h3>
          <p>角色：{{ userRole || 'admin' }}</p>
        </el-card>

        <el-card class="mb-16">
          <div slot="header" class="clearfix">
            <span>系统统计</span>
          </div>
          <el-row :gutter="12" v-if="stats">
            <el-col :span="4"><div class="stat"><div class="label">用户数</div><div class="value">{{ stats.users }}</div></div></el-col>
            <el-col :span="4"><div class="stat"><div class="label">厂家数</div><div class="value">{{ stats.manufacturers }}</div></div></el-col>
            <el-col :span="4"><div class="stat"><div class="label">招标单位</div><div class="value">{{ stats.tender_units }}</div></div></el-col>
            <el-col :span="4"><div class="stat"><div class="label">招标项目</div><div class="value">{{ stats.tenders }}</div></div></el-col>
            <el-col :span="4"><div class="stat"><div class="label">投标数</div><div class="value">{{ stats.bids }}</div></div></el-col>
            <el-col :span="4"><div class="stat"><div class="label">留言数</div><div class="value">{{ stats.messages }}</div></div></el-col>
          </el-row>
          <div v-else>加载中...</div>
        </el-card>

        <el-card class="mb-16">
          <div slot="header" class="clearfix">
            <span>用户管理</span>
            <div style="float:right;">
              <el-input v-model="userKeyword" placeholder="用户名" clearable size="small" style="width:200px; margin-right:8px;"
                        @keyup.enter.native="searchUsers" @clear="searchUsers" />
              <el-select v-model="filterRole" placeholder="筛选角色" size="small" @change="loadUsers" style="width:160px;">
                <el-option label="全部" value="" />
                <el-option label="厂家" value="manufacturer" />
                <el-option label="招标单位" value="tender" />
                <el-option label="管理员" value="admin" />
              </el-select>
              <el-button size="small" type="primary" style="margin-left:8px;" @click="searchUsers">搜索</el-button>
            </div>
          </div>
          <div class="filter-info" style="margin-bottom:8px; color:#666; font-size:12px;">
            <span>当前筛选：</span>
            <span>角色：{{ roleLabel || '全部' }}</span>
            <span style="margin-left:12px;">关键词：{{ (userKeyword||'').trim() || '—' }}</span>
            <span style="margin-left:12px;">结果：{{ userTotal }} 条</span>
          </div>
          <el-table :data="filteredUsers" style="width:100%" v-if="filteredUsers.length">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="username" label="用户名" />
            <el-table-column prop="email" label="邮箱" />
            <el-table-column label="角色" width="110">
              <template slot-scope="scope">{{ roleText(scope.row.role) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template slot-scope="scope">
                <span class="status-badge" :class="'status-' + (scope.row.status || '')">{{ statusText(scope.row.status) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态操作" width="240">
              <template slot-scope="scope">
                <el-button v-if="scope.row.role !== 'admin'" type="success" size="mini" @click="updateStatus(scope.row.id, 'active')">通过</el-button>
                <el-button v-if="scope.row.role !== 'admin'" type="danger" size="mini" @click="updateStatus(scope.row.id, 'rejected')">拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else>未找到匹配的用户</div>
          <div style="margin-top:12px;" v-if="userTotal > 0">
            <el-pagination
              @size-change="handleUserSizeChange"
              @current-change="handleUserPageChange"
              :current-page.sync="userPage"
              :page-sizes="[5,10,20,50]"
              :page-size.sync="userPageSize"
              background
              layout="prev, pager, next, sizes, total"
              :total="userTotal">
            </el-pagination>
          </div>
        </el-card>

        <el-card class="mb-16">
          <div slot="header" class="clearfix">
            <span>留言管理</span>
            <div style="float:right;">
              <el-button type="text" @click="$router.push('/messages')">进入留言板</el-button>
            </div>
          </div>
          <el-table :data="messages" style="width:100%" v-if="messages.length">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="title" label="标题" />
            <el-table-column prop="author" label="作者" width="160" />
            <el-table-column prop="content" label="内容" />
            <el-table-column label="操作" width="120">
              <template slot-scope="scope">
                <el-button type="danger" size="mini" @click="deleteMessage(scope.row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else>暂无留言</div>
          <div style="margin-top:12px;" v-if="messageTotal > 0">
            <el-pagination
              @size-change="handleMessageSizeChange"
              @current-change="handleMessagePageChange"
              :current-page.sync="messagePage"
              :page-sizes="[5,10,20,50]"
              :page-size.sync="messagePageSize"
              background
              layout="prev, pager, next, sizes, total"
              :total="messageTotal">
            </el-pagination>
          </div>
        </el-card>

        <el-card class="mb-16">
          <div slot="header" class="clearfix">
            <span>招标管理</span>
            <div style="float:right;">
              <el-input v-model="tenderKeyword" placeholder="项目编号/设备/型号/单位" clearable size="small" style="width:220px; margin-right:8px;"
                        @keyup.enter.native="searchAdminTenders" @clear="searchAdminTenders" />
              <el-select v-model="tenderFilterStatus" placeholder="筛选状态" size="small" @change="loadTenders" style="width:160px;">
                <el-option label="全部" value="" />
                <el-option label="开放" value="open" />
                <el-option label="关闭" value="closed" />
                <el-option label="取消" value="cancelled" />
              </el-select>
              <el-button size="small" type="primary" style="margin-left:8px;" @click="searchAdminTenders">搜索</el-button>
            </div>
          </div>
          <div class="filter-info" style="margin-bottom:8px; color:#666; font-size:12px;">
            <span>当前筛选：</span>
            <span>状态：{{ statusLabel || '全部' }}</span>
            <span style="margin-left:12px;">关键词：{{ (tenderKeyword||'').trim() || '—' }}</span>
            <span style="margin-left:12px;">结果：{{ tenderTotal }} 条</span>
          </div>
          <el-table :data="filteredTenders" style="width:100%" v-if="filteredTenders.length">
            <el-table-column prop="project_number" label="项目编号" width="140" />
            <el-table-column prop="equipment_name" label="设备名称" />
            <el-table-column prop="tender_unit_name" label="招标单位" width="160" />
            <el-table-column prop="quantity" label="数量" width="100" />
            <el-table-column prop="deadline" label="截止日期" width="140" />
            <el-table-column prop="status" label="状态" width="120" />
            <el-table-column label="操作" width="220">
              <template slot-scope="scope">
                <el-button type="warning" size="mini" @click="editTender(scope.row)">修改状态</el-button>
                <el-button type="danger" size="mini" @click="deleteTender(scope.row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else-if="isLoadingTenders">加载中...</div>
          <div v-else>{{ emptyTenderMessage }}</div>
          <div style="margin-top:12px;" v-if="tenderTotal > 0">
            <el-pagination
              @size-change="handleTenderSizeChange"
              @current-change="handleTenderPageChange"
              :current-page.sync="tenderPage"
              :page-sizes="[5,10,20,50]"
              :page-size.sync="tenderPageSize"
              background
              layout="prev, pager, next, sizes, total"
              :total="tenderTotal">
            </el-pagination>
          </div>
        </el-card>

        <el-dialog title="修改招标状态" :visible.sync="editDialogVisible" width="420px">
          <el-form label-width="100px" @submit.native.prevent>
            <el-form-item label="状态">
              <el-select v-model="editStatus" style="width: 240px;">
                <el-option label="开放" value="open" />
                <el-option label="关闭" value="closed" />
                <el-option label="取消" value="cancelled" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="updateTenderStatus">保存</el-button>
              <el-button @click="editDialogVisible=false">取消</el-button>
            </el-form-item>
          </el-form>
        </el-dialog>
      </el-main>
    </el-container>
  </div>
</template>

<script>
import { adminApi, tenderApi } from '@/services/api'
export default {
  name: 'AdminDashboard',
  data() {
    return {
      stats: null,
      users: [],
      userPage: 1,
      userPageSize: 10,
      userTotal: 0,
      messages: [],
      messagePage: 1,
      messagePageSize: 10,
      messageTotal: 0,
      userKeyword: '',
      filterRole: '',
      tenders: [],
      tenderPage: 1,
      tenderPageSize: 10,
      tenderTotal: 0,
      tenderFilterStatus: '',
      tenderKeyword: '',
      isLoadingTenders: false,
      editDialogVisible: false,
      editTenderId: null,
      editStatus: ''
    }
  },
  computed: {
    currentUser() { return this.$store.getters.currentUser },
    userRole() { return this.$store.getters.userRole },
    emptyTenderMessage() {
      const kw = (this.tenderKeyword || '').trim()
      return kw || this.tenderFilterStatus ? '未找到匹配的招标项目' : '暂无招标数据'
    },
    statusLabel() {
      const m = { open: '开放', closed: '关闭', cancelled: '取消' }
      return m[this.tenderFilterStatus] || ''
    },
    roleLabel() {
      const m = { manufacturer: '厂家', tender: '招标单位', admin: '管理员' }
      return m[this.filterRole] || ''
    },
    filteredUsers() {
      const list = this.users || []
      const kw = (this.userKeyword || '').trim().toLowerCase()
      if (!kw) return list
      const contains = (v) => (v || '').toString().toLowerCase().includes(kw)
      return list.filter(u => contains(u.username))
    },
    filteredTenders() {
      const list = this.tenders || []
      const kw = (this.tenderKeyword || '').trim().toLowerCase()
      if (!kw) return list
      const contains = (v) => (v || '').toString().toLowerCase().includes(kw)
      return list.filter(t =>
        contains(t.project_number) ||
        contains(t.equipment_name) ||
        contains(t.model) ||
        contains(t.description) ||
        contains(t.packaging_requirements) ||
        contains(t.tender_unit_name)
      )
    }
  },
  created() {
    this.loadStats()
    this.loadUsers()
    this.loadMessages()
    this.loadTenders()
  },
  methods: {
    normalizeListPagination(res, page, pageSize) {
      if (Array.isArray(res)) {
        const total = res.length
        const offset = Math.max(0, (page - 1) * pageSize)
        const rows = res.slice(offset, offset + pageSize)
        return { rows, total }
      }
      if (res && Array.isArray(res.items)) {
        return { rows: res.items, total: Number(res.total) || res.items.length || 0 }
      }
      if (res && Array.isArray(res.data)) {
        return { rows: res.data, total: Number(res.total) || res.data.length || 0 }
      }
      return { rows: [], total: 0 }
    },
    logout() {
      this.$store.dispatch('logout')
      this.$message.success('已退出登录')
      this.$router.push('/')
    },
    roleText(role) {
      return { manufacturer: '厂家', tender: '招标单位', admin: '管理员' }[role] || role
    },
    statusText(status) {
      return { active: '已通过', pending: '待审核', rejected: '已拒绝' }[status] || status || '—'
    },
    async loadStats() {
      try { this.stats = await adminApi.getStatistics() } catch (e) { console.error('统计加载失败', e) }
    },
    async loadUsers() {
      try {
        const params = { page: this.userPage, pageSize: this.userPageSize }
        if (this.filterRole) params.role = this.filterRole
        const kw = (this.userKeyword || '').trim()
        if (kw) params.keyword = kw
        const res = await adminApi.getUsers(params)
        const { rows, total } = this.normalizeListPagination(res, this.userPage, this.userPageSize)
        this.users = rows
        this.userTotal = total
      } catch (e) { console.error('用户加载失败', e) }
    },
    async searchUsers() {
      await this.loadUsers()
    },
    async handleUserPageChange(page) {
      this.userPage = page
      await this.loadUsers()
    },
    async handleUserSizeChange(size) {
      this.userPageSize = size
      this.userPage = 1
      await this.loadUsers()
    },
    async updateStatus(userId, status) {
      try { await adminApi.updateUserStatus(userId, status); this.$message.success('已更新'); this.loadUsers() } catch (e) { console.error('更新状态失败', e) }
    },
    async loadMessages() {
      try {
        const res = await adminApi.getMessages({ page: this.messagePage, pageSize: this.messagePageSize })
        const { rows, total } = this.normalizeListPagination(res, this.messagePage, this.messagePageSize)
        this.messages = rows
        this.messageTotal = total
      } catch (e) { console.error('留言加载失败', e) }
    },
    async handleMessagePageChange(page) {
      this.messagePage = page
      await this.loadMessages()
    },
    async handleMessageSizeChange(size) {
      this.messagePageSize = size
      this.messagePage = 1
      await this.loadMessages()
    },
    async deleteMessage(id) {
      try { await adminApi.deleteMessage(id); this.$message.success('已删除'); this.loadMessages() } catch (e) { console.error('删除失败', e) }
    },
    async loadTenders() {
      this.isLoadingTenders = true
      try {
        const params = { page: this.tenderPage, pageSize: this.tenderPageSize }
        if (this.tenderFilterStatus) params.status = this.tenderFilterStatus
        const kw = (this.tenderKeyword || '').trim()
        if (kw) params.keyword = kw
        const res = await adminApi.getTenders(params)
        const { rows, total } = this.normalizeListPagination(res, this.tenderPage, this.tenderPageSize)
        this.tenders = rows
        this.tenderTotal = total
      } catch (e) {
        console.error('加载招标失败', e)
        this.$message.error('加载招标失败，请稍后再试')
      } finally {
        this.isLoadingTenders = false
      }
    },
    async searchAdminTenders() { await this.loadTenders() },
    async handleTenderPageChange(page) {
      this.tenderPage = page
      await this.loadTenders()
    },
    async handleTenderSizeChange(size) {
      this.tenderPageSize = size
      this.tenderPage = 1
      await this.loadTenders()
    },
    editTender(row) {
      this.editTenderId = row.id
      this.editStatus = row.status
      this.editDialogVisible = true
    },
    async updateTenderStatus() {
      try { await tenderApi.updateTender(this.editTenderId, { status: this.editStatus }); this.$message.success('状态已更新'); this.editDialogVisible=false; this.loadTenders() } catch (e) { console.error('更新失败', e) }
    },
    async deleteTender(id) {
      try { await tenderApi.deleteTender(id); this.$message.success('已删除'); this.loadTenders() } catch (e) { console.error('删除失败', e) }
    }
  }
}
</script>

<style scoped>
.dashboard-header { background: linear-gradient(135deg, #1d4ed8, #0ea5e9); color: white; }
.header-content { display: flex; justify-content: space-between; align-items: center; }
.mb-16 { margin-bottom: 16px; }
.stat { background: #f8fafc; border: 1px solid #e5eaf2; padding: 14px 8px; border-radius: 12px; text-align: center; }
.stat .label { color: #6b7280; font-size: 13px; }
.stat .value { font-size: 24px; font-weight: 700; color: #1d4ed8; margin-top: 6px; }
</style>
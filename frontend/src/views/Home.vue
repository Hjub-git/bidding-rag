<template>
  <div class="home-container">
    <el-container>
      <el-header>
        <div class="header-content">
          <div class="logo">机电设备在线招投标系统</div>
          <div class="nav-links">
            <template v-if="isLoggedIn">
              <el-button v-if="userRole === 'admin'" type="text" @click="goToDashboard">进入系统</el-button>
              <el-button v-if="userRole === 'manufacturer'" type="text" @click="goToMyBids">我的投标</el-button>
              <el-button v-if="userRole === 'tender'" type="text" @click="handlePublishTender">发布招标</el-button>
              <el-button type="text" @click="goMessages">留言板</el-button>
              <el-button type="text" @click="logout">退出登录</el-button>
            </template>
            <template v-else>
              <el-button type="text" @click="$router.push('/login')">登录</el-button>
              <el-button type="text" @click="$router.push('/register')">注册</el-button>
              <el-button type="text" @click="goMessages">留言板</el-button>
            </template>
          </div>
        </div>
      </el-header>
      
      <el-main>
        <div class="hero">
          <h1>欢迎使用机电设备在线招投标系统</h1>
          <p>为设备生产企业与招标单位提供便捷的在线招投标服务，覆盖招标发布、投标、评标定标与留言沟通全流程。</p>
        </div>

        <!-- 招标单位：明确"这里是全部公开项目，你的项目在工作台" -->
        <el-alert
          v-if="isLoggedIn && userRole === 'tender'"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 16px;"
        >
          <template slot="title">
            你正在浏览<b>全部公开项目</b>；发布招标、管理自己的项目，请进入工作台
            <el-button type="primary" size="mini" style="margin-left: 12px;" @click="goToDashboard">进入我的工作台</el-button>
          </template>
        </el-alert>
        <el-row :gutter="20">
          <el-col :span="16">
            <el-card class="welcome-card">
              <h2>最新招标项目</h2>
              <el-form :inline="true" :model="tenderFilters" class="tender-filter-bar" @submit.native.prevent>
                <el-form-item label="关键字">
                  <el-input
                    v-model="tenderFilters.keyword"
                    placeholder="项目编号/设备/型号/单位"
                    clearable
                    style="width: 260px"
                    @keyup.enter.native="searchTenders"
                    @clear="searchTenders"
                  />
                </el-form-item>
                <el-form-item label="状态">
                  <el-select v-model="tenderFilters.status" placeholder="请选择状态" style="width: 160px" @change="onStatusChange">
                    <el-option label="开放中" value="open" />
                    <el-option label="已截止" value="closed" />
                  </el-select>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="searchTenders">搜索</el-button>
                  <el-button @click="resetTenderFilters">重置</el-button>
                </el-form-item>
              </el-form>
              <el-table :data="tenders" style="width: 100%" v-loading="loadingTenders">
                <el-table-column prop="project_number" label="项目编号" width="120"></el-table-column>
                <el-table-column prop="equipment_name" label="设备名称"></el-table-column>
                <el-table-column prop="model" label="型号" width="120"></el-table-column>
                <el-table-column prop="quantity" label="数量" width="80"></el-table-column>
                <el-table-column prop="deadline" label="截止日期" width="120"></el-table-column>
                <el-table-column label="状态" width="100">
                  <template slot-scope="scope">
                    <span class="status-badge" :class="'status-' + scope.row.status">{{ scope.row.status === 'open' ? '开放中' : scope.row.status === 'closed' ? '已截止' : scope.row.status }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="200">
                  <template slot-scope="scope">
                    <el-button type="text" size="small" @click="viewTenderDetails(scope.row)">查看详情</el-button>
                    <!-- 投标按钮只对厂商出现：招标单位/管理员/未登录都不该看到"立即投标" -->
                    <template v-if="isManufacturer">
                      <el-divider direction="vertical"></el-divider>
                      <!-- T23：已投过的项目显示「已投标」，点击进「我的投标」；否则仍是「立即投标」 -->
                      <el-tag
                        v-if="hasBid(scope.row)"
                        type="success"
                        size="small"
                        class="bid-done-tag"
                        @click="goToMyBids"
                      >已投标</el-tag>
                      <el-button v-else type="text" size="small" @click="openBid(scope.row)">立即投标</el-button>
                    </template>
                    <template v-else-if="isLoggedIn && userRole === 'tender'">
                      <el-divider direction="vertical"></el-divider>
                      <el-button type="text" size="small" @click="goToDashboard">管理我的招标</el-button>
                    </template>
                    <template v-else-if="!isLoggedIn">
                      <el-divider direction="vertical"></el-divider>
                      <el-button type="text" size="small" @click="goToLogin">登录后投标</el-button>
                    </template>
                  </template>
                </el-table-column>
              </el-table>
              <div style="display:flex; justify-content: space-between; align-items:center; margin-top: 12px;">
                <div style="color:#909399;">共 {{ pagination.total }} 条，当前第 {{ pagination.page }} 页</div>
                <el-pagination
                  background
                  layout="prev, pager, next, sizes, total"
                  :total="pagination.total"
                  :current-page="pagination.page"
                  :page-size="pagination.pageSize"
                  :page-sizes="[5, 10, 20]"
                  @current-change="handlePageChange"
                  @size-change="handleSizeChange"
                />
              </div>
            </el-card>
          </el-col>
          
          <el-col :span="8">
            <el-card class="info-card rag-card">
              <h3>招标文件智能问答</h3>
              <div class="guide-links">
                <p class="rag-desc">基于招标文件检索增强（RAG），回答带 [S1] 溯源与页码</p>
                <el-select
                  v-model="ragPickTenderId"
                  class="rag-tender-select"
                  placeholder="选择要提问的项目"
                  filterable
                  clearable
                  no-data-text="当前页没有项目，请先在上方列表切页"
                >
                  <el-option
                    v-for="item in tenders"
                    :key="item.id"
                    :label="item.project_number + ' ' + item.equipment_name"
                    :value="item.id"
                  />
                </el-select>
                <el-button
                  type="success"
                  icon="el-icon-chat-dot-round"
                  class="rag-open-btn"
                  @click="openRagFromCard"
                >开始问答</el-button>
                <p class="rag-hint">未登录会先跳登录；没有可选项目时请先在上方列表切页</p>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-main>
      
      <el-footer>
        <div class="footer-content">
          <p>© 2025 机电设备在线招投标系统 · Spring Boot 3 + Vue 2 + MySQL</p>
        </div>
      </el-footer>
    </el-container>
    
    <!-- 招标项目详情对话框 -->
    <el-dialog title="招标项目详情" :visible.sync="dialogVisible" width="60%">
      <div v-if="currentTender">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="项目编号">{{ currentTender.project_number }}</el-descriptions-item>
          <el-descriptions-item label="设备名称">{{ currentTender.equipment_name }}</el-descriptions-item>
          <el-descriptions-item label="型号">{{ currentTender.model }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ currentTender.quantity }}</el-descriptions-item>
          <el-descriptions-item label="截止日期">{{ currentTender.deadline }}</el-descriptions-item>
          <el-descriptions-item label="投标保证金">{{ currentTender.deposit }}</el-descriptions-item>
          <el-descriptions-item label="包装要求" :span="2">{{ currentTender.packaging_requirements }}</el-descriptions-item>
          <el-descriptions-item label="项目描述" :span="2">{{ currentTender.description }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button
          type="success"
          icon="el-icon-chat-dot-round"
          @click="openRagChat"
        >智能问答</el-button>
        <!-- 角色化：厂商显示投标（已投过则显示已投标）；招标单位显示去管理；未登录显示登录 -->
        <template v-if="isManufacturer">
          <el-button v-if="hasBid(currentTender)" type="success" plain @click="goToMyBids">已投标（查看）</el-button>
          <el-button v-else type="primary" @click="openBid(currentTender)">立即投标</el-button>
        </template>
        <el-button v-else-if="isLoggedIn && userRole === 'tender'" type="warning" @click="goToDashboard">去工作台管理</el-button>
        <el-button v-else-if="!isLoggedIn" type="primary" @click="goToLogin">登录参与投标</el-button>
      </span>
    </el-dialog>

    <!-- 招标文件智能问答抽屉 -->
    <rag-chat-drawer
      :visible.sync="ragVisible"
      :tender-id="currentTender ? currentTender.id : null"
      :tender-label="ragTenderLabel"
    />

    <!-- 提交投标对话框 -->
    <el-dialog title="提交投标" :visible.sync="bidDialogVisible" width="40%">
      <div v-if="selectedTender">
        <el-form :model="bidForm" label-width="120px" @submit.native.prevent>
          <el-form-item label="投标总金额">
            <el-input v-model.number="bidForm.total_amount" placeholder="请输入总金额" />
          </el-form-item>
          <el-form-item label="生产日期">
            <el-date-picker v-model="bidForm.production_date" type="date" value-format="yyyy-MM-dd" placeholder="请选择日期" />
          </el-form-item>
          <el-form-item label="质保期">
            <el-input v-model="bidForm.warranty_period" placeholder="如：24 个月" />
          </el-form-item>
          <el-form-item label="保证金缴纳日期">
            <el-date-picker v-model="bidForm.deposit_payment_date" type="date" value-format="yyyy-MM-dd" placeholder="请选择日期" />
          </el-form-item>
          <el-form-item label="投标文件链接">
            <el-input v-model="bidForm.bid_document" placeholder="可选，提供网盘或链接" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submittingBid" :disabled="submittingBid" @click="submitBid">提交</el-button>
            <el-button @click="bidDialogVisible=false">取消</el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { tenderApi } from '@/services/api'
import { bidApi } from '@/services/api'
import RagChatDrawer from '@/components/RagChatDrawer.vue'
export default {
  name: 'Home',
  components: { RagChatDrawer },
  data() {
    return {
      dialogVisible: false,
      currentTender: null,
      ragVisible: false,
      ragPickTenderId: null,
      tenders: [],
      loadingTenders: false,
      pagination: { page: 1, pageSize: 5, total: 0 },
      tenderFilters: { keyword: '', status: 'open' },
      bidDialogVisible: false,
      selectedTender: null,
      submittingBid: false,
      bidForm: {
        total_amount: null,
        production_date: '',
        warranty_period: '',
        deposit_payment_date: '',
        bid_document: ''
      },
      // T23：当前厂家「已投标」的项目 id 集合（数组，元素为 Number）
      // 只有 MANUFACTURER 会填充；其它角色保持空数组，按钮照旧角色化
      myBidProjectIds: [],
      notices: []
    }
  },
  computed: {
    isLoggedIn() {
      // 修正：使用已定义的 getter isAuthenticated
      return this.$store.getters.isAuthenticated
    },
    userRole() {
      return this.$store.getters.userRole
    },
    // 只有「已登录 + 厂家」才谈得上投标/已投标
    isManufacturer() {
      return this.isLoggedIn && this.userRole === 'manufacturer'
    },
    ragTenderLabel() {
      const tender = this.currentTender
      if (!tender) return ''
      return [tender.project_number, tender.equipment_name].filter(Boolean).join(' · ')
    }
  },
  async mounted() {
    try {
      await this.loadTenders()
    } catch (e) {
      console.error('首页加载招标失败', e)
      this.$message.error(e.response?.data?.message || '加载招标列表失败')
    }
    // T23：厂家视角补一次「我的投标」，把已投项目标成「已投标」；
    // 方法内部自己按角色兜底，非厂家/未登录不会发请求
    this.loadMyBidProjectIds()
  },
  watch: {
    // 登录态变化（例如在别处登录后回到首页）时，厂家需要重新拉一次已投标集合
    isManufacturer(val) {
      if (val) {
        this.loadMyBidProjectIds()
      } else {
        this.myBidProjectIds = []
      }
    }
  },
  methods: {
    // T23：拉「我的投标」，把 tender_project_id 收集成数组，供首页标记「已投标」
    // 后端 /api/bids/mine 只有 MANUFACTURER 可调（@PreAuthorize），所以先按角色拦住
    async loadMyBidProjectIds() {
      if (!this.isLoggedIn || this.userRole !== 'manufacturer') {
        this.myBidProjectIds = []
        return
      }
      try {
        const res = await bidApi.getMyBids()
        // 兼容三种返回：裸数组 / {items:[]} / {data:[]}
        let list = []
        if (Array.isArray(res)) {
          list = res
        } else if (res && Array.isArray(res.items)) {
          list = res.items
        } else if (res && Array.isArray(res.data)) {
          list = res.data
        }
        const ids = list
          .map(bid => {
            if (!bid) return NaN
            const raw = bid.tender_project_id !== undefined ? bid.tender_project_id : bid.tenderProjectId
            return Number(raw)
          })
          .filter(id => Number.isFinite(id))
        // 去重：同一项目原则上只有一条，历史/并发数据下也不重复渲染
        this.myBidProjectIds = Array.from(new Set(ids))
      } catch (e) {
        // 拉不到只是少了个状态标记，不该影响首页浏览，静默降级为「立即投标」
        // eslint-disable-next-line no-console
        console.warn('加载我的投标失败，已按未投标展示', e)
        this.myBidProjectIds = []
      }
    },
    // 判断某个招标项目当前厂家是否已投标
    hasBid(tender) {
      if (!tender || tender.id === undefined || tender.id === null) return false
      return this.myBidProjectIds.indexOf(Number(tender.id)) !== -1
    },
    normalizeListResponse(res) {
      // 后端分页统一返回 { items, total }
      if (Array.isArray(res)) {
        return { rows: res, total: res.length }
      }
      if (res && Array.isArray(res.items)) {
        return { rows: res.items, total: Number(res.total) || res.items.length || 0 }
      }
      if (res && Array.isArray(res.data)) {
        return { rows: res.data, total: Number(res.total) || res.data.length || 0 }
      }
      return { rows: [], total: 0 }
    },
    async loadTenders() {
      this.loadingTenders = true
      try {
        const res = await tenderApi.getTenders({
          keyword: this.tenderFilters.keyword || undefined,
          status: this.tenderFilters.status || undefined,
          page: this.pagination.page,
          pageSize: this.pagination.pageSize
        })
        const { rows, total } = this.normalizeListResponse(res)
        this.tenders = rows
        this.pagination.total = total || 0
      } finally {
        this.loadingTenders = false
      }
    },
    async searchTenders() {
      try {
        this.pagination.page = 1
        await this.loadTenders()
      } catch (e) {
        console.error('搜索招标失败', e)
        this.$message.error(e.response?.data?.message || '搜索失败')
      }
    },
    async onStatusChange() {
      this.pagination.page = 1
      await this.loadTenders()
    },
    async resetTenderFilters() {
      this.tenderFilters = { keyword: '', status: 'open' }
      try {
        this.pagination.page = 1
        await this.loadTenders()
      } catch (e) {
        console.error('重置筛选失败', e)
      }
    },
    async handlePageChange(p) {
      this.pagination.page = p
      await this.loadTenders()
    },
    async handleSizeChange(size) {
      this.pagination.pageSize = size
      this.pagination.page = 1
      await this.loadTenders()
    },
    viewTenderDetails(tender) {
      this.currentTender = tender
      this.dialogVisible = true
    },
    // 首页右侧「招标文件智能问答」卡片的主入口：先选项目，再打开抽屉
    openRagFromCard() {
      // 问答接口在 SecurityConfig 里要求登录，未登录先引导去登录（与详情弹窗入口一致）
      if (!this.isLoggedIn) {
        this.$message.info('请先登录后再使用智能问答')
        this.$router.push('/login')
        return
      }
      if (!this.ragPickTenderId) {
        this.$message.warning('请先选择一个项目')
        return
      }
      const picked = this.tenders.find(t => t.id === this.ragPickTenderId)
      if (!picked) {
        this.$message.error('项目信息缺失，请刷新')
        return
      }
      // 复用现有抽屉：抽屉读的是 currentTender，这里把它切到卡片选中的项目
      this.currentTender = picked
      this.ragVisible = true
    },
    openRagChat() {
      // 问答接口在 SecurityConfig 里要求登录，未登录先引导去登录
      if (!this.isLoggedIn) {
        this.$message.info('请先登录后再使用智能问答')
        this.$router.push('/login')
        return
      }
      if (!this.currentTender || !this.currentTender.id) {
        this.$message.error('缺少招标项目信息，无法打开智能问答')
        return
      }
      this.ragVisible = true
    },
    goToLogin() {
      this.dialogVisible = false
      this.$router.push('/login')
    },
    goToDashboard() {
      if (this.userRole === 'manufacturer') {
        this.$router.push('/manufacturer')
      } else if (this.userRole === 'tender') {
        this.$router.push('/tender')
      } else if (this.userRole === 'admin') {
        this.$router.push('/admin')
      }
    },
    goToMyBids() {
      // 厂家查看“我的投标”
      this.$router.push('/manufacturer')
    },
    handlePublishTender() {
      if (this.userRole === 'tender') {
        this.$router.push('/tender')
      } else {
        this.$message.error('请使用招标单位账号登录后发布招标')
      }
    },
    goMessages() {
      this.$router.push('/messages')
    },
    openBid(tender) {
      if (!this.isLoggedIn) {
        this.$message.info('请先登录后再投标')
        this.$router.push('/login')
        return
      }
      if (this.userRole !== 'manufacturer') {
        this.$message.error('只有厂商角色可以参与投标')
        return
      }
      // T23：已投过的项目不再开空表单（后端唯一索引会拒），直接引导到「我的投标」
      if (this.hasBid(tender)) {
        this.$message.info('该项目你已投标，可在「我的投标」查看或撤回')
        this.$router.push('/manufacturer')
        return
      }
      this.selectedTender = tender
      this.bidDialogVisible = true
    },
    async submitBid() {
      if (!this.selectedTender) return
      if (!this.bidForm.total_amount || this.bidForm.total_amount <= 0) {
        this.$message.error('请填写有效的投标总金额')
        return
      }
      this.submittingBid = true
      try {
        const payload = {
          tender_project_id: this.selectedTender.id,
          total_amount: this.bidForm.total_amount,
          production_date: this.bidForm.production_date || null,
          warranty_period: this.bidForm.warranty_period || null,
          deposit_payment_date: this.bidForm.deposit_payment_date || null,
          bid_document: this.bidForm.bid_document || null
        }
        await bidApi.createBid(payload)
        this.$message.success('投标提交成功')
        // T23：本地立即标记为「已投标」，不用重拉列表；失败重试/撤回后刷新页面即可同步
        const submittedId = Number(payload.tender_project_id)
        if (Number.isFinite(submittedId) && this.myBidProjectIds.indexOf(submittedId) === -1) {
          this.myBidProjectIds = this.myBidProjectIds.concat(submittedId)
        }
        this.bidDialogVisible = false
        this.bidForm = { total_amount: null, production_date: '', warranty_period: '', deposit_payment_date: '', bid_document: '' }
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '提交投标失败'
        this.$message.error(msg)
        console.error('提交投标失败', e)
      } finally {
        this.submittingBid = false
      }
    },
    logout() {
      this.$store.dispatch('logout')
      this.$message.success('已退出登录')
      this.$router.push('/')
    }
  }
}
</script>

<style scoped>
/* T23：表格里的「已投标」标签，点击可进「我的投标」，给个可点的视觉提示 */
.bid-done-tag {
  cursor: pointer;
}

/* 首页右侧「招标文件智能问答」卡片：只补必要间距，配色沿用 styles.css 的 .info-card */
.rag-card .rag-desc {
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
  margin: 0 0 12px;
}

.rag-card .rag-tender-select {
  width: 100%;
  margin-bottom: 12px;
}

.rag-card .rag-open-btn {
  width: 100%;
}

.rag-card .rag-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin: 12px 0 0;
}
</style>



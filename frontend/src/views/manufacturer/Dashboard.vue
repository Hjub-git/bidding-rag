<template>
  <div class="dashboard">
    <el-container>
      <el-header class="dashboard-header">
        <div class="header-content">
          <h2>我的投标</h2>
          <div>
            <el-button type="text" @click="$router.push('/')">返回首页</el-button>
            <el-button type="text" @click="logout">退出登录</el-button>
          </div>
        </div>
      </el-header>
      <el-main>
        <el-card class="mb-16">
          <h3>欢迎，{{ currentUser ? currentUser.username : '厂家用户' }}</h3>
          <p>角色：{{ userRole || 'manufacturer' }}</p>
        </el-card>

        <el-card>
          <div slot="header">我的投标项目</div>
          <el-table :data="myBids" style="width:100%" v-if="myBids.length">
            <el-table-column prop="project_number" label="项目编号" width="140" />
            <el-table-column prop="equipment_name" label="设备名称" />
            <el-table-column prop="model" label="型号" width="140" />
            <el-table-column prop="tender_unit_name" label="招标单位" width="160" />
            <el-table-column prop="deadline" label="截止日期" width="140" />
            <el-table-column prop="total_amount" label="投标总金额" width="140" />
            <el-table-column label="投标状态" width="120">
              <template slot-scope="scope">
                <span class="status-badge" :class="'status-' + scope.row.bid_status">{{ bidStatusText(scope.row.bid_status) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220">
              <template slot-scope="scope">
                <el-button type="warning" size="mini" :disabled="scope.row.bid_status!=='submitted'" @click="openEdit(scope.row)">修改</el-button>
                <el-button type="danger" size="mini" :disabled="scope.row.bid_status!=='submitted'" @click="withdrawBid(scope.row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else>暂无投标记录</div>
        </el-card>
        <!-- 编辑投标对话框 -->
        <el-dialog title="修改投标" :visible.sync="editDialogVisible" width="40%">
          <div v-if="editingBid">
            <el-form :model="editForm" label-width="120px" @submit.native.prevent>
              <el-form-item label="投标总金额">
                <el-input v-model.number="editForm.total_amount" placeholder="请输入总金额" />
              </el-form-item>
              <el-form-item label="生产日期">
                <el-date-picker v-model="editForm.production_date" type="date" value-format="yyyy-MM-dd" placeholder="请选择日期" />
              </el-form-item>
              <el-form-item label="保质期">
                <el-input v-model="editForm.warranty_period" placeholder="如：24 个月" />
              </el-form-item>
              <el-form-item label="保证金缴纳日期">
                <el-date-picker v-model="editForm.deposit_payment_date" type="date" value-format="yyyy-MM-dd" placeholder="请选择日期" />
              </el-form-item>
              <el-form-item label="投标文件链接">
                <el-input v-model="editForm.bid_document" placeholder="可选，提供网盘或链接" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="submittingEdit" :disabled="submittingEdit" @click="submitEdit">保存</el-button>
                <el-button @click="editDialogVisible=false">取消</el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-dialog>
      </el-main>
    </el-container>
  </div>
</template>

<script>
import { bidApi } from '@/services/api'
export default {
  name: 'ManufacturerDashboard',
  data() {
    return {
      myBids: [],
      editDialogVisible: false,
      editingBid: null,
      editForm: {
        total_amount: null,
        production_date: '',
        warranty_period: '',
        deposit_payment_date: '',
        bid_document: ''
      },
      submittingEdit: false
    }
  },
  computed: {
    currentUser() { return this.$store.getters.currentUser },
    userRole() { return this.$store.getters.userRole }
  },
  created() {
    this.loadMyBids()
  },
  methods: {
    logout() {
      this.$store.dispatch('logout')
      this.$message.success('已退出登录')
      this.$router.push('/')
    },
    bidStatusText(status) {
      return { submitted: '待处理', accepted: '已接受', rejected: '已拒绝' }[status] || status
    },
    async loadMyBids() {
      try {
        this.myBids = await bidApi.getMyBids()
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '加载我的投标失败'
        if (e.response?.status === 400 && /厂家信息不存在/.test(msg)) {
          this.$message.error('厂家信息不存在：请先完善厂家资料或使用正确的厂家账号登录')
        } else if (e.response?.status === 401) {
          this.$message.error('未授权：请重新登录')
          this.$router.push('/login')
        } else {
          this.$message.error(msg)
        }
        console.error('加载我的投标失败', e)
      }
    },
    openEdit(row) {
      this.editingBid = row
      this.editForm = {
        total_amount: row.total_amount,
        production_date: row.production_date,
        warranty_period: row.warranty_period,
        deposit_payment_date: row.deposit_payment_date,
        bid_document: row.bid_document || ''
      }
      this.editDialogVisible = true
    },
    async submitEdit() {
      if (!this.editingBid) return
      if (!this.editForm.total_amount || this.editForm.total_amount <= 0) {
        this.$message.error('请填写有效的投标总金额')
        return
      }
      this.submittingEdit = true
      try {
        await bidApi.updateBid(this.editingBid.id, {
          total_amount: this.editForm.total_amount,
          production_date: this.editForm.production_date,
          warranty_period: this.editForm.warranty_period,
          deposit_payment_date: this.editForm.deposit_payment_date,
          bid_document: this.editForm.bid_document || null
        })
        this.$message.success('修改成功')
        this.editDialogVisible = false
        await this.loadMyBids()
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '修改投标失败'
        this.$message.error(msg)
        console.error('修改投标失败', e)
      } finally {
        this.submittingEdit = false
      }
    },
    async withdrawBid(row) {
      try {
        await this.$confirm('确定要撤销此投标吗？仅可撤销未处理的投标。', '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      try {
        await bidApi.deleteBid(row.id)
        this.$message.success('撤销成功')
        await this.loadMyBids()
      } catch (e) {
        const msg = e.response?.data?.message || e.message || '撤销投标失败'
        this.$message.error(msg)
        console.error('撤销投标失败', e)
      }
    }
    
  }
}
</script>

<style scoped>
.dashboard-header {
  background: linear-gradient(135deg, #16a34a, #22c55e);
  color: white;
}
.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.mb-16 { margin-bottom: 16px; }
</style>
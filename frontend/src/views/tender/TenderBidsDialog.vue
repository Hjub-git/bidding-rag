<template>
  <el-dialog
    :title="dialogTitle"
    :visible="visible"
    width="820px"
    @update:visible="val => $emit('update:visible', val)"
    @open="loadBids"
  >
    <div v-loading="loading">
      <el-table :data="bids" style="width:100%" v-if="bids.length">
        <el-table-column prop="manufacturer_name" label="厂家" />
        <el-table-column prop="total_amount" label="总金额" width="120" />
        <el-table-column prop="production_date" label="生产日期" width="130" />
        <el-table-column prop="warranty_period" label="保质期" width="110" />
        <el-table-column prop="deposit_payment_date" label="保证金缴款日期" width="150" />
        <el-table-column label="状态" width="110">
          <template slot-scope="scope">
            <span class="status-badge" :class="'status-' + scope.row.status">{{ bidStatusText(scope.row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template slot-scope="scope">
            <el-button
              type="success"
              size="mini"
              :disabled="scope.row.status !== 'submitted'"
              @click="decide(scope.row, 'accept')"
            >接受</el-button>
            <el-button
              type="danger"
              size="mini"
              :disabled="scope.row.status !== 'submitted'"
              @click="decide(scope.row, 'reject')"
            >拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="bids-empty">该项目还没有厂家投标</div>
    </div>
  </el-dialog>
</template>

<script>
import { bidApi } from '@/services/api'

/**
 * T17：从旧 tender/Dashboard.vue 里搬出来的「投标列表」弹窗。
 * 只负责展示某个招标项目的投标并按 submitted 状态接受/拒绝，逻辑与旧实现一致。
 */
export default {
  name: 'TenderBidsDialog',
  props: {
    visible: { type: Boolean, default: false },
    tender: { type: Object, default: null }
  },
  data() {
    return {
      loading: false,
      bids: []
    }
  },
  computed: {
    dialogTitle() {
      if (!this.tender) return '投标列表'
      const label = [this.tender.project_number, this.tender.equipment_name].filter(Boolean).join(' · ')
      return label ? '投标列表 · ' + label : '投标列表'
    }
  },
  methods: {
    bidStatusText(status) {
      return { submitted: '待处理', accepted: '已接受', rejected: '已拒绝' }[status] || status
    },
    async loadBids() {
      if (!this.tender || !this.tender.id) {
        this.bids = []
        return
      }
      this.loading = true
      try {
        const res = await bidApi.getBids({ tenderProjectId: this.tender.id })
        this.bids = res || []
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '加载投标失败'
        this.$message.error(msg)
        this.bids = []
      } finally {
        this.loading = false
      }
    },
    async decide(bid, action) {
      const verb = action === 'accept' ? '接受' : '拒绝'
      try {
        await this.$confirm(`确定${verb}该投标？`, `确认${verb}`, {
          type: 'warning',
          confirmButtonText: '确定',
          cancelButtonText: '取消'
        })
      } catch (e) {
        return
      }
      try {
        if (action === 'accept') {
          await bidApi.acceptBid(bid.id)
        } else {
          await bidApi.rejectBid(bid.id)
        }
        this.$message.success('已' + verb)
        await this.loadBids()
        this.$emit('changed')
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || verb + '失败'
        this.$message.error(msg)
      }
    }
  }
}
</script>

<style scoped>
.bids-empty {
  padding: 28px 0;
  text-align: center;
  color: #909399;
}
</style>

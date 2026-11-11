<template>
  <div class="tender-list">
    <!-- ── 页头：标题 + 右上「发布招标」 ── -->
    <div class="page-head">
      <div>
        <h3 class="page-title">我的招标</h3>
        <p class="page-desc">仅显示本单位发布的招标项目</p>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="$router.push('/tender/projects/new')">发布招标</el-button>
    </div>

    <el-card class="mb-16">
      <!-- ── 状态 Tab（带数量角标） ── -->
      <el-tabs v-model="statusFilter" class="status-tabs">
        <!-- Tab 与下面的「状态」下拉共用 statusFilter，两个控件永远同步，不会互相打架 -->
        <el-tab-pane v-for="tab in tabs" :key="tab.name" :name="tab.name">
          <span slot="label">
            {{ tab.label }}
            <span class="tab-count" :class="{ 'is-active': statusFilter === tab.name }">{{ counts[tab.name] }}</span>
          </span>
        </el-tab-pane>
      </el-tabs>

      <!-- ── 筛选 ── -->
      <el-form :inline="true" class="filter-bar" @submit.native.prevent>
        <el-form-item label="关键字">
          <el-input
            v-model="keyword"
            placeholder="项目编号 / 设备名称 / 型号"
            clearable
            style="width: 240px"
            @clear="resetPage"
            @keyup.enter.native="resetPage"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="statusFilter" placeholder="全部" style="width: 140px" @change="resetPage">
            <el-option label="全部" value="all" />
            <el-option label="进行中" value="open" />
            <el-option label="已截止" value="closed" />
            <el-option label="已取消" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="resetPage">搜索</el-button>
          <el-button icon="el-icon-refresh" :loading="loading" @click="loadTenders">刷新</el-button>
        </el-form-item>
      </el-form>

      <!-- ── 列表（空态 / 筛选无结果 / 表格三态互斥） ── -->
      <div v-loading="loading" class="table-wrap">
        <div v-if="!loading && !allTenders.length" class="empty-state">
          <i class="el-icon-document-add"></i>
          <p>还没有招标项目，去发布</p>
          <el-button type="primary" size="small" @click="$router.push('/tender/projects/new')">发布招标</el-button>
        </div>
        <div v-else-if="!loading && !filteredTenders.length" class="empty-state">
          <i class="el-icon-search"></i>
          <p>换个条件试试</p>
          <el-button size="small" @click="resetFilters">重置筛选</el-button>
        </div>
        <el-table v-else :data="pagedTenders" style="width:100%" border>
          <el-table-column prop="project_number" label="项目编号" width="140" />
          <el-table-column prop="equipment_name" label="设备名称" min-width="140" />
          <el-table-column prop="model" label="型号" width="130" />
          <el-table-column prop="quantity" label="数量" width="90" align="right" />
          <el-table-column label="截止日期" width="120">
            <template slot-scope="scope">{{ formatDate(scope.row.deadline) || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template slot-scope="scope">
              <span class="status-badge" :class="'status-' + scope.row.status">{{ statusText(scope.row.status) }}</span>
            </template>
          </el-table-column>
          <!-- 行操作顺序按设计约定：查看投标 / 智能问答 / 上传文件 / 编辑 / 删除 -->
          <el-table-column label="操作" width="430">
            <template slot-scope="scope">
              <el-button type="primary" size="mini" @click="openBids(scope.row)">查看投标</el-button>
              <el-button type="success" size="mini" @click="openRagChat(scope.row)">智能问答</el-button>
              <el-button type="warning" size="mini" @click="openUpload(scope.row)">上传文件</el-button>
              <el-button size="mini" @click="openEdit(scope.row)">编辑</el-button>
              <el-button type="danger" size="mini" @click="removeTender(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- ── 前端分页（my-tenders 返回 List） ── -->
      <div v-if="filteredTenders.length" class="pager">
        <el-pagination
          background
          :current-page.sync="page"
          :page-size.sync="pageSize"
          :page-sizes="[5, 10, 20]"
          layout="total, sizes, prev, pager, next"
          :total="filteredTenders.length"
          @size-change="resetPage"
        />
      </div>
    </el-card>

    <!-- ── 投标列表弹窗（U2 前置：从旧 Dashboard 搬出） ── -->
    <tender-bids-dialog :visible.sync="bidsVisible" :tender="bidsTender" />

    <!-- ── 上传招标文件弹窗（U2：el-upload + http-request 自定义 → /api/rag/upload） ── -->
    <el-dialog
      title="上传招标文件"
      :visible.sync="uploadVisible"
      width="520px"
      @closed="onUploadClosed"
    >
      <p class="upload-hint">
        上传到：<b>{{ uploadLabel || '—' }}</b><br>
        选中文件后立即上传并入库，支持 pdf / docx / pptx / xlsx / txt，单个 ≤ 50MB。
      </p>
      <el-upload
        ref="uploader"
        drag
        action=""
        :show-file-list="true"
        :limit="10"
        :accept="accept"
        :disabled="uploading"
        :before-upload="beforeUpload"
        :http-request="doUpload"
      >
        <i class="el-icon-upload"></i>
        <div class="el-upload__text">把文件拖到此处，或<em>点击选择</em></div>
      </el-upload>
      <div v-if="uploading" class="upload-status">
        <i class="el-icon-loading"></i> 正在上传并入库，请勿关闭页面…
      </div>
    </el-dialog>

    <!-- ── 编辑弹窗（沿用旧 Dashboard 的字段与 updateTender） ── -->
    <el-dialog title="编辑招标" :visible.sync="editVisible" width="600px">
      <el-form :model="editForm" label-width="110px" @submit.native.prevent>
        <el-form-item label="设备名称"><el-input v-model="editForm.equipmentName" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="editForm.model" /></el-form-item>
        <el-form-item label="包装要求">
          <el-input type="textarea" v-model="editForm.packagingRequirements" />
        </el-form-item>
        <el-form-item label="数量"><el-input v-model.number="editForm.quantity" /></el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="editForm.deadline" type="date" value-format="yyyy-MM-dd" placeholder="选择日期" />
        </el-form-item>
        <el-form-item label="保证金"><el-input v-model.number="editForm.deposit" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editForm.status">
            <el-option label="进行中" value="open" />
            <el-option label="已截止" value="closed" />
            <el-option label="已取消" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="updateTender">保存</el-button>
          <el-button @click="editVisible = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>

    <!-- 智能问答抽屉：只引用，不改其内部实现 -->
    <rag-chat-drawer
      :visible.sync="ragVisible"
      :tender-id="ragTenderId"
      :tender-label="ragTenderLabel"
    />
  </div>
</template>

<script>
import { tenderApi } from '@/services/api'
import RagChatDrawer from '@/components/RagChatDrawer.vue'
import TenderBidsDialog from './TenderBidsDialog.vue'
import {
  UPLOAD_ACCEPT,
  uploadTenderDocument,
  uploadResultText,
  validateUploadFile
} from '@/utils/ragUpload'

const STATUS_TEXT = { open: '进行中', closed: '已截止', cancelled: '已取消' }

export default {
  name: 'TenderProjects',
  components: { RagChatDrawer, TenderBidsDialog },
  data() {
    return {
      loading: false,
      allTenders: [],
      // Tab 与筛选下拉共用同一个状态源，避免两个控件互相打架
      statusFilter: 'all',
      keyword: '',
      page: 1,
      pageSize: 10,
      tabs: [
        { name: 'all', label: '全部' },
        { name: 'open', label: '进行中' },
        { name: 'closed', label: '已截止' },
        { name: 'cancelled', label: '已取消' }
      ],
      // 投标弹窗
      bidsVisible: false,
      bidsTender: null,
      // 上传
      accept: UPLOAD_ACCEPT,
      uploadVisible: false,
      uploadTender: null,
      uploadLabel: '',
      uploading: false,
      // 编辑
      editVisible: false,
      saving: false,
      editId: null,
      editForm: {
        equipmentName: '',
        model: '',
        packagingRequirements: '',
        quantity: null,
        deadline: '',
        deposit: null,
        status: 'open'
      },
      // 智能问答
      ragVisible: false,
      ragTenderId: null,
      ragTenderLabel: ''
    }
  },
  computed: {
    counts() {
      const base = { all: this.allTenders.length, open: 0, closed: 0, cancelled: 0 }
      this.allTenders.forEach(t => {
        if (Object.prototype.hasOwnProperty.call(base, t.status) && t.status !== 'all') {
          base[t.status] += 1
        }
      })
      return base
    },
    filteredTenders() {
      const kw = (this.keyword || '').trim().toLowerCase()
      return this.allTenders.filter(t => {
        if (this.statusFilter !== 'all' && t.status !== this.statusFilter) return false
        if (!kw) return true
        const hay = [t.project_number, t.equipment_name, t.model]
          .filter(Boolean)
          .join(' ')
          .toLowerCase()
        return hay.indexOf(kw) !== -1
      })
    },
    pagedTenders() {
      const start = (this.page - 1) * this.pageSize
      return this.filteredTenders.slice(start, start + this.pageSize)
    }
  },
  created() {
    this.loadTenders()
  },
  methods: {
    statusText(status) {
      return STATUS_TEXT[status] || status || '—'
    },
    formatDate(val) {
      if (!val) return ''
      // 后端返回 yyyy-MM-dd 字符串，直接截断，避免 new Date 造成时区偏移
      if (typeof val === 'string') return val.slice(0, 10)
      const d = new Date(val)
      if (isNaN(d.getTime())) return ''
      const m = (d.getMonth() + 1).toString().padStart(2, '0')
      const day = d.getDate().toString().padStart(2, '0')
      return `${d.getFullYear()}-${m}-${day}`
    },
    resetPage() {
      this.page = 1
    },
    resetFilters() {
      this.keyword = ''
      this.statusFilter = 'all'
      this.page = 1
    },
    async loadTenders() {
      this.loading = true
      try {
        const list = await tenderApi.getMyTenders()
        this.allTenders = Array.isArray(list) ? list : []
        // 删除/筛选后当前页可能越界，兜到最后一页
        const maxPage = Math.max(1, Math.ceil(this.filteredTenders.length / this.pageSize))
        if (this.page > maxPage) this.page = maxPage
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '加载招标项目失败'
        this.$message.error(msg)
      } finally {
        this.loading = false
      }
    },

    // ── 查看投标 ──
    openBids(tender) {
      this.bidsTender = tender
      this.bidsVisible = true
    },

    // ── 智能问答 ──
    openRagChat(tender) {
      if (!tender || !tender.id) {
        this.$message.error('缺少招标项目信息，无法打开智能问答')
        return
      }
      this.ragTenderId = tender.id
      this.ragTenderLabel = [tender.project_number, tender.equipment_name].filter(Boolean).join(' · ')
      this.ragVisible = true
    },

    // ── 上传文件（U2） ──
    openUpload(tender) {
      this.uploadTender = tender
      this.uploadLabel = [tender.project_number, tender.equipment_name].filter(Boolean).join(' · ')
      this.uploadVisible = true
    },
    beforeUpload(file) {
      const err = validateUploadFile(file)
      if (err) {
        this.$message.error(err)
        return false
      }
      return true
    },
    async doUpload(options) {
      this.uploading = true
      try {
        const doc = await uploadTenderDocument(this.uploadTender && this.uploadTender.id, options.file)
        this.$message.success(uploadResultText(doc))
        this.uploadVisible = false
        return doc
      } catch (e) {
        this.$message.error(e.message)
        throw e
      } finally {
        this.uploading = false
      }
    },
    onUploadClosed() {
      const up = this.$refs.uploader
      if (up && typeof up.clearFiles === 'function') up.clearFiles()
      this.uploadTender = null
      this.uploadLabel = ''
    },

    // ── 编辑 ──
    openEdit(tender) {
      this.editId = tender.id
      this.editForm = {
        equipmentName: tender.equipment_name || '',
        model: tender.model || '',
        packagingRequirements: tender.packaging_requirements || '',
        quantity: tender.quantity,
        deadline: this.formatDate(tender.deadline),
        deposit: tender.deposit,
        status: tender.status || 'open'
      }
      this.editVisible = true
    },
    async updateTender() {
      if (!this.editId) {
        this.$message.error('未选择要修改的招标项目')
        return
      }
      this.saving = true
      try {
        await tenderApi.updateTender(this.editId, {
          equipment_name: this.editForm.equipmentName,
          model: this.editForm.model,
          packaging_requirements: this.editForm.packagingRequirements,
          quantity: this.editForm.quantity,
          deadline: this.formatDate(this.editForm.deadline),
          deposit: this.editForm.deposit,
          status: this.editForm.status
        })
        this.$message.success('已更新')
        this.editVisible = false
        this.editId = null
        await this.loadTenders()
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '更新失败'
        this.$message.error(msg)
      } finally {
        this.saving = false
      }
    },

    // ── 删除（保留二次确认） ──
    async removeTender(row) {
      if (!row || !row.id) {
        this.$message.error('未选择要删除的招标项目')
        return
      }
      // 二次确认：逻辑删除后项目会从列表/首页消失，误点一次就是"项目没了"。
      try {
        await this.$confirm(
          '删除后该项目将不再展示（逻辑删除，数据仍保留在库中）。确定删除？',
          '确认删除',
          { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
        )
      } catch (e) {
        return // 用户取消
      }
      try {
        await tenderApi.deleteTender(row.id)
        this.$message.success('已删除')
        await this.loadTenders()
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '删除失败'
        this.$message.error(msg)
      }
    }
  }
}
</script>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}
.page-desc {
  margin: 0;
  font-size: 13px;
  color: #909399;
}
.mb-16 {
  margin-bottom: 16px;
}
.status-tabs ::v-deep .el-tabs__content {
  display: none;
}
.status-tabs ::v-deep .el-tabs__header {
  margin-bottom: 12px;
}
.tab-count {
  display: inline-block;
  min-width: 20px;
  margin-left: 4px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  color: #606266;
  background: #f0f2f5;
  border-radius: 9px;
}
.tab-count.is-active {
  color: #fff;
  background: #d97706;
}
.filter-bar {
  margin-bottom: 4px;
}
.filter-bar ::v-deep .el-form-item {
  margin-bottom: 12px;
}
.table-wrap {
  min-height: 120px;
}
.empty-state {
  padding: 40px 0;
  text-align: center;
  color: #909399;
}
.empty-state i {
  font-size: 40px;
  color: #c0c4cc;
}
.empty-state p {
  margin: 12px 0 16px;
  font-size: 14px;
}
.pager {
  margin-top: 14px;
  text-align: right;
}
.upload-hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
}
.upload-status {
  margin-top: 10px;
  font-size: 13px;
  color: #e6a23c;
}
</style>

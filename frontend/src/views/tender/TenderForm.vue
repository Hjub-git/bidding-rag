<template>
  <div class="tender-form">
    <div class="page-head">
      <div>
        <h3 class="page-title">发布招标</h3>
        <p class="page-desc">分四块填写：基本信息 → 需求说明 → 时间与保证金 → 招标文件</p>
      </div>
      <el-button icon="el-icon-back" @click="$router.push('/tender/projects')">返回列表</el-button>
    </div>

    <el-form ref="form" :model="form" :rules="rules" label-width="110px" @submit.native.prevent>
      <!-- ① 基本信息 -->
      <el-card class="mb-16">
        <div slot="header" class="card-title">
          <i class="el-icon-document"></i> 基本信息
        </div>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="项目编号" prop="projectNumber">
              <el-input v-model.trim="form.projectNumber" placeholder="如 TP-2026-013" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备名称" prop="equipmentName">
              <el-input v-model.trim="form.equipmentName" placeholder="如 高压电机" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="型号" prop="model">
              <el-input v-model.trim="form.model" placeholder="如 YB2-355" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数量" prop="quantity">
              <el-input v-model.number="form.quantity" placeholder="正整数" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-card>

      <!-- ② 需求说明 -->
      <el-card class="mb-16">
        <div slot="header" class="card-title">
          <i class="el-icon-edit-outline"></i> 需求说明
        </div>
        <el-form-item label="包装要求">
          <el-input type="textarea" :rows="2" v-model="form.packagingRequirements" placeholder="如 木箱包装、防潮" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input type="textarea" :rows="3" v-model="form.description" placeholder="技术要求、交货期等补充说明（可选）" />
        </el-form-item>
      </el-card>

      <!-- ③ 时间与保证金 -->
      <el-card class="mb-16">
        <div slot="header" class="card-title">
          <i class="el-icon-time"></i> 时间与保证金
        </div>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="截止日期" prop="deadline">
              <el-date-picker
                v-model="form.deadline"
                type="date"
                value-format="yyyy-MM-dd"
                placeholder="选择日期"
                :picker-options="deadlinePickerOptions"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="投标保证金" prop="deposit">
              <el-input v-model.number="form.deposit" placeholder="单位：元，不能为负" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-card>

      <!-- ④ 招标文件（U2 上传，发布成功后自动上传） -->
      <el-card class="mb-16">
        <div slot="header" class="card-title">
          <i class="el-icon-upload2"></i> 招标文件
        </div>
        <el-upload
          ref="uploader"
          drag
          action=""
          :file-list="fileList"
          :auto-upload="false"
          :accept="accept"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
          :on-exceed="onFileExceed"
        >
          <i class="el-icon-upload"></i>
          <div class="el-upload__text">把招标文件拖到此处，或<em>点击选择</em></div>
          <div slot="tip" class="el-upload__tip">
            支持 pdf / docx / pptx / xlsx / txt，单个 ≤ 50MB。项目发布成功后自动上传并入库（可稍后在列表页「上传文件」补传）。
          </div>
        </el-upload>
      </el-card>

      <div class="form-actions">
        <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">发布</el-button>
        <el-button :disabled="submitting" @click="resetAll">重置</el-button>
      </div>
    </el-form>
  </div>
</template>

<script>
import { tenderApi } from '@/services/api'
import {
  UPLOAD_ACCEPT,
  uploadTenderDocument,
  uploadResultText,
  validateUploadFile
} from '@/utils/ragUpload'

function todayStart() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d
}

export default {
  name: 'TenderCreate',
  data() {
    const validateQuantity = (rule, value, callback) => {
      if (value === '' || value === null || value === undefined) {
        callback(new Error('请填写数量'))
        return
      }
      const n = Number(value)
      if (!Number.isFinite(n) || !Number.isInteger(n) || n <= 0) {
        callback(new Error('数量必须是大于 0 的整数'))
        return
      }
      callback()
    }
    const validateDeposit = (rule, value, callback) => {
      if (value === '' || value === null || value === undefined) {
        callback(new Error('请填写投标保证金'))
        return
      }
      const n = Number(value)
      // 后端 TenderRequest 是 @DecimalMin("0")，保证金 0（免缴）是合法值，这里只拦负数
      if (!Number.isFinite(n) || n < 0) {
        callback(new Error('保证金不能为负数'))
        return
      }
      callback()
    }
    const validateDeadline = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请选择截止日期'))
        return
      }
      const d = new Date(String(value).slice(0, 10) + 'T00:00:00')
      if (isNaN(d.getTime())) {
        callback(new Error('截止日期格式不正确'))
        return
      }
      if (d.getTime() < todayStart().getTime()) {
        callback(new Error('截止日期不能早于今天'))
        return
      }
      callback()
    }
    return {
      submitting: false,
      accept: UPLOAD_ACCEPT,
      fileList: [],
      pendingFile: null,
      form: {
        projectNumber: '',
        equipmentName: '',
        model: '',
        quantity: null,
        packagingRequirements: '',
        description: '',
        deadline: '',
        deposit: null
      },
      rules: {
        // 后端 TenderRequest 的 projectNumber 是 @NotBlank，前端必须一起拦，否则提交才报 400
        projectNumber: [{ required: true, message: '请填写项目编号', trigger: 'blur' }],
        equipmentName: [{ required: true, message: '请填写设备名称', trigger: 'blur' }],
        model: [{ required: true, message: '请填写型号', trigger: 'blur' }],
        quantity: [{ required: true, validator: validateQuantity, trigger: 'blur' }],
        deadline: [{ required: true, validator: validateDeadline, trigger: 'change' }],
        deposit: [{ required: true, validator: validateDeposit, trigger: 'blur' }]
      },
      deadlinePickerOptions: {
        disabledDate(time) {
          return time.getTime() < todayStart().getTime()
        }
      }
    }
  },
  methods: {
    // ── 招标文件选择（不自动上传，发布成功后用返回的项目 id 上传） ──
    onFileChange(file, fileList) {
      const raw = file && file.raw
      if (!raw) return
      const err = validateUploadFile(raw)
      if (err) {
        this.$message.error(err)
        this.fileList = []
        this.pendingFile = null
        return
      }
      // limit=1：只保留最后选中的那个
      this.fileList = fileList.slice(-1)
      this.pendingFile = raw
    },
    onFileRemove() {
      this.fileList = []
      this.pendingFile = null
    },
    onFileExceed() {
      this.$message.warning('只能上传 1 个招标文件，请先移除已选文件')
    },

    // ── 提交 ──
    validateForm() {
      return new Promise((resolve, reject) => {
        this.$refs.form.validate(valid => (valid ? resolve() : reject(new Error('表单校验未通过'))))
      })
    },
    async submit() {
      try {
        await this.validateForm()
      } catch (e) {
        this.$message.error('请先补全标红的必填项')
        return
      }
      this.submitting = true
      try {
        // 后端全局 Jackson 是 SNAKE_CASE，请求体必须用 snake_case
        const created = await tenderApi.createTender({
          project_number: this.form.projectNumber,
          equipment_name: this.form.equipmentName,
          model: this.form.model,
          packaging_requirements: this.form.packagingRequirements,
          quantity: this.form.quantity,
          deadline: this.form.deadline,
          deposit: this.form.deposit,
          description: this.form.description
        })
        // 选文件了就在发布成功后立刻上传（失败不回滚项目，只提示，可在列表页补传）
        if (this.pendingFile && created && created.id) {
          try {
            const doc = await uploadTenderDocument(created.id, this.pendingFile)
            this.$message.success(uploadResultText(doc))
          } catch (e) {
            this.$message.error('项目已发布，但招标文件上传失败：' + e.message)
          }
        }
        this.$message.success('发布成功')
        this.$router.push('/tender/projects')
      } catch (e) {
        const msg = (e.response && e.response.data && e.response.data.message) || e.message || '发布失败'
        if (e.response && e.response.status === 403) {
          this.$message.error('权限不足：请使用招标单位账号登录')
        } else if (e.response && e.response.status === 400 && /招标单位信息不存在/.test(msg)) {
          this.$message.error('招标单位信息不存在：请先完善招标单位资料')
        } else {
          this.$message.error(msg)
        }
      } finally {
        this.submitting = false
      }
    },
    resetAll() {
      this.$refs.form.resetFields()
      this.fileList = []
      this.pendingFile = null
      const up = this.$refs.uploader
      if (up && typeof up.clearFiles === 'function') up.clearFiles()
      this.form.quantity = null
      this.form.deposit = null
      this.form.deadline = ''
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
.card-title {
  font-weight: 600;
  color: #303133;
}
.form-actions {
  padding: 4px 0 24px;
}
</style>

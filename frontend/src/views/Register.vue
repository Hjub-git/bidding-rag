<template>
  <div class="auth-page">
    <el-card class="auth-card register-card">
      <div class="auth-brand">
        <h2>机电设备在线招投标系统</h2>
        <p>创建账号，选择厂家或招标单位身份</p>
      </div>
      <el-form :model="registerForm" :rules="rules" ref="registerForm" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="registerForm.username" placeholder="请输入用户名"></el-input>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" type="password" placeholder="请输入密码"></el-input>
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码"></el-input>
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="registerForm.email" placeholder="请输入邮箱"></el-input>
        </el-form-item>
        <el-form-item label="用户类型" prop="role">
          <el-radio-group v-model="registerForm.role">
            <el-radio label="manufacturer">机电设备生产厂家</el-radio>
            <el-radio label="tender">招标单位</el-radio>
          </el-radio-group>
        </el-form-item>
        
        <!-- 机电设备生产厂家特有字段 -->
        <template v-if="registerForm.role === 'manufacturer'">
          <el-form-item label="公司名称" prop="companyName">
            <el-input v-model="registerForm.companyName" placeholder="请输入公司名称"></el-input>
          </el-form-item>
          <el-form-item label="法人代表" prop="legalPerson">
            <el-input v-model="registerForm.legalPerson" placeholder="请输入法人代表姓名"></el-input>
          </el-form-item>
          <el-form-item label="联系电话" prop="contactPhone">
            <el-input v-model="registerForm.contactPhone" placeholder="请输入联系电话"></el-input>
          </el-form-item>
          <el-form-item label="公司地址" prop="address">
            <el-input v-model="registerForm.address" placeholder="请输入公司地址"></el-input>
          </el-form-item>
          <el-form-item label="营业执照" prop="businessLicense">
            <el-input v-model="registerForm.businessLicense" placeholder="请输入营业执照号码"></el-input>
          </el-form-item>
          <el-form-item label="公司简介" prop="companyProfile">
            <el-input type="textarea" v-model="registerForm.companyProfile" placeholder="请输入公司简介"></el-input>
          </el-form-item>
        </template>
        
        <!-- 招标单位特有字段 -->
        <template v-if="registerForm.role === 'tender'">
          <el-form-item label="单位名称" prop="companyName">
            <el-input v-model="registerForm.companyName" placeholder="请输入单位名称"></el-input>
          </el-form-item>
          <el-form-item label="法人代表" prop="legalPerson">
            <el-input v-model="registerForm.legalPerson" placeholder="请输入法人代表姓名"></el-input>
          </el-form-item>
          <el-form-item label="联系电话" prop="contactPhone">
            <el-input v-model="registerForm.contactPhone" placeholder="请输入联系电话"></el-input>
          </el-form-item>
          <el-form-item label="单位地址" prop="address">
            <el-input v-model="registerForm.address" placeholder="请输入单位地址"></el-input>
          </el-form-item>
          <el-form-item label="营业执照" prop="businessLicense">
            <el-input v-model="registerForm.businessLicense" placeholder="请输入营业执照号码"></el-input>
          </el-form-item>
          <el-form-item label="单位简介" prop="companyProfile">
            <el-input type="textarea" v-model="registerForm.companyProfile" placeholder="请输入单位简介"></el-input>
          </el-form-item>
        </template>
        
        <el-form-item>
          <el-button type="primary" @click="submitForm('registerForm')" :loading="loading">注册</el-button>
          <el-button @click="resetForm('registerForm')">重置</el-button>
        </el-form-item>
        <div class="login-link">
          <router-link to="/login">已有账号？立即登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'Register',
  data() {
    // 确认密码验证
    const validatePass = (rule, value, callback) => {
      if (value !== this.registerForm.password) {
        callback(new Error('两次输入密码不一致!'))
      } else {
        callback()
      }
    }
    return {
      loading: false,
      registerForm: {
        username: '',
        password: '',
        confirmPassword: '',
        email: '',
        role: 'manufacturer',
        companyName: '',
        legalPerson: '',
        contactPhone: '',
        address: '',
        businessLicense: '',
        companyProfile: ''
      },
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
        ],
        confirmPassword: [
          { required: true, message: '请再次输入密码', trigger: 'blur' },
          { validator: validatePass, trigger: 'blur' }
        ],
        email: [
          { required: true, message: '请输入邮箱地址', trigger: 'blur' },
          { type: 'email', message: '请输入正确的邮箱地址', trigger: 'blur' }
        ],
        role: [
          { required: true, message: '请选择用户类型', trigger: 'change' }
        ],
        companyName: [
          { required: true, message: '请输入公司/单位名称', trigger: 'blur' }
        ],
        legalPerson: [
          { required: true, message: '请输入法人代表姓名', trigger: 'blur' }
        ],
        contactPhone: [
          { required: true, message: '请输入联系电话', trigger: 'blur' },
          { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号码', trigger: 'blur' }
        ],
        address: [
          { required: true, message: '请输入地址', trigger: 'blur' }
        ],
        businessLicense: [
          { required: true, message: '请输入营业执照号码', trigger: 'blur' }
        ]
      }
    }
  },
  methods: {
    submitForm(formName) {
      this.$refs[formName].validate(async (valid) => {
        if (valid) {
          this.loading = true
          try {
            // 构建注册数据（后端接口为扁平结构 + snake_case 字段名）
            const userData = {
              username: this.registerForm.username,
              password: this.registerForm.password,
              email: this.registerForm.email,
              role: this.registerForm.role,
              company_name: this.registerForm.companyName,
              legal_person: this.registerForm.legalPerson,
              contact_phone: this.registerForm.contactPhone,
              address: this.registerForm.address,
              business_license: this.registerForm.businessLicense,
              company_profile: this.registerForm.companyProfile
            }
            
            await this.$store.dispatch('register', userData)
            this.$message.success('注册成功')
            
            // 根据角色跳转到不同的页面
            const role = this.$store.getters.userRole
            if (role === 'manufacturer') {
              this.$router.push('/manufacturer')
            } else if (role === 'tender') {
              this.$router.push('/tender')
            } else {
              this.$router.push('/')
            }
          } catch (error) {
            this.$message.error('注册失败: ' + (error.response?.data?.message || '未知错误'))
          } finally {
            this.loading = false
          }
        } else {
          return false
        }
      })
    },
    resetForm(formName) {
      this.$refs[formName].resetFields()
    }
  }
}
</script>

<style scoped>
.register-card {
  width: 640px;
}

.login-link {
  text-align: center;
  margin-top: 6px;
  font-size: 14px;
}

.login-link a {
  color: var(--brand-1);
  text-decoration: none;
}
</style>
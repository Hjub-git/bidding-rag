<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <div class="auth-brand">
        <h2>机电设备在线招投标系统</h2>
        <p>登录后管理招标、投标与审核流程</p>
      </div>
      <el-form :model="loginForm" :rules="rules" ref="loginForm" @submit.native.prevent>
        <el-form-item prop="username">
          <el-input v-model="loginForm.username" placeholder="请输入用户名" prefix-icon="el-icon-user" size="medium"></el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="el-icon-lock" size="medium" @keyup.enter.native="submitForm('loginForm')"></el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width:100%" size="medium" @click="submitForm('loginForm')" :loading="loading">登 录</el-button>
        </el-form-item>
      </el-form>
      <div class="register-link">
        <router-link to="/register">没有账号？立即注册</router-link>
      </div>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'Login',
  data() {
    return {
      loading: false,
      loginForm: {
        username: '',
        password: ''
      },
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '长度在 6 到 20 个字符', trigger: 'blur' }
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
            await this.$store.dispatch('login', this.loginForm)
            const role = this.$store.getters.userRole
            
            // 根据角色跳转到不同的页面
            if (role === 'manufacturer') {
              this.$router.push('/manufacturer')
            } else if (role === 'tender') {
              this.$router.push('/tender')
            } else if (role === 'admin') {
              this.$router.push('/admin')
            } else {
              this.$router.push('/')
            }
            
            this.$message.success('登录成功')
          } catch (error) {
            this.$message.error('登录失败: ' + (error.response?.data?.message || '未知错误'))
          } finally {
            this.loading = false
          }
        } else {
          return false
        }
      })
    }
  }
}
</script>

<style scoped>
.register-link {
  text-align: center;
  margin-top: 6px;
  font-size: 14px;
}

.register-link a {
  color: var(--brand-1);
  text-decoration: none;
}
</style>

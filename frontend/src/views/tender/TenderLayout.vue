<template>
  <div class="tender-layout">
    <el-container class="layout-shell">
      <!-- 顶部：标题 + 用户名 + 返回首页 / 退出登录（沿用旧 Dashboard 的头部） -->
      <el-header class="dashboard-header">
        <div class="header-content">
          <div class="header-title">
            <h2>招标单位控制台</h2>
            <span class="header-user">{{ currentUser ? currentUser.username : '招标用户' }}</span>
          </div>
          <div class="header-actions">
            <el-button type="text" @click="$router.push('/')">返回首页</el-button>
            <el-button type="text" @click="logout">退出登录</el-button>
          </div>
        </div>
      </el-header>

      <el-container class="layout-body">
        <!-- 左侧导航；窄屏（<1200px）折叠成顶部横向 -->
        <el-aside class="layout-aside" :class="{ 'is-narrow': narrow }" :width="narrow ? '100%' : '186px'">
          <el-menu
            :default-active="$route.path"
            :mode="narrow ? 'horizontal' : 'vertical'"
            :collapse-transition="false"
            class="layout-menu"
            router
          >
            <el-menu-item index="/tender/projects">
              <i class="el-icon-tickets"></i>
              <span slot="title">我的招标</span>
            </el-menu-item>
            <el-menu-item index="/tender/projects/new">
              <i class="el-icon-plus"></i>
              <span slot="title">发布招标</span>
            </el-menu-item>
          </el-menu>
        </el-aside>

        <el-main class="layout-main">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script>
export default {
  name: 'TenderLayout',
  data() {
    return {
      // 窄屏断点：<1200px 时菜单从左侧竖排改为顶部横排（不做移动端深度适配）
      narrow: false
    }
  },
  computed: {
    currentUser() {
      return this.$store.getters.currentUser
    }
  },
  created() {
    this.syncWidth()
    window.addEventListener('resize', this.syncWidth)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.syncWidth)
  },
  methods: {
    logout() {
      this.$store.dispatch('logout')
      this.$message.success('已退出登录')
      this.$router.push('/')
    },
    syncWidth() {
      this.narrow = window.innerWidth < 1200
    }
  }
}
</script>

<style scoped>
.layout-shell {
  min-height: 100vh;
}
/* 招标单位主题色：与旧 Dashboard 的头部保持一致（styles.css 里的 .dashboard-header 只给了高度/阴影） */
.dashboard-header {
  background: linear-gradient(135deg, #d97706, #f59e0b);
}
.header-title {
  display: flex;
  align-items: baseline;
}
.header-user {
  margin-left: 12px;
  font-size: 14px;
  opacity: 0.9;
}
.layout-body {
  align-items: stretch;
}
.layout-aside {
  padding: 12px 8px;
  background: #fff;
  box-sizing: border-box;
}
.layout-aside.is-narrow {
  padding: 0 8px;
  border-bottom: 1px solid #ebeef5;
}
.layout-menu {
  border-right: none;
}
.layout-aside.is-narrow .layout-menu {
  display: flex;
  justify-content: center;
  border-bottom: none;
}
.layout-main {
  padding: 16px;
  min-width: 0;
}
</style>

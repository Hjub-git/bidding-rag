import Vue from 'vue'
import VueRouter from 'vue-router'
import Home from '../views/Home.vue'
import Login from '../views/Login.vue'
import Register from '../views/Register.vue'
import ManufacturerDashboard from '../views/manufacturer/Dashboard.vue'
// T17：招标单位工作台按「方案 B」拆成 布局 / 列表 / 发布 三个页面。
// 旧 views/tender/Dashboard.vue 保留但不再被路由引用（仅存档）。
import TenderLayout from '../views/tender/TenderLayout.vue'
import TenderList from '../views/tender/TenderList.vue'
import TenderForm from '../views/tender/TenderForm.vue'
import AdminDashboard from '../views/admin/Dashboard.vue'
import MessageBoard from '../views/messages/MessageBoard.vue'
import MessageDetail from '../views/messages/MessageDetail.vue'

Vue.use(VueRouter)

const routes = [
  {
    path: '/',
    name: 'Home',
    component: Home
  },
  {
    path: '/messages',
    name: 'MessageBoard',
    component: MessageBoard
  },
  {
    path: '/messages/:id',
    name: 'MessageDetail',
    component: MessageDetail,
    props: true
  },
  {
    path: '/login',
    name: 'Login',
    component: Login
  },
  {
    path: '/register',
    name: 'Register',
    component: Register
  },
  {
    path: '/manufacturer',
    name: 'ManufacturerDashboard',
    component: ManufacturerDashboard,
    meta: { requiresAuth: true, role: 'manufacturer' }
  },
  {
    // T17 方案 B：/tender 兼容旧链接（首页提示条、登录跳转、顶部导航都还能落到 /tender）
    path: '/tender',
    component: TenderLayout,
    meta: { requiresAuth: true, role: 'tender' },
    children: [
      {
        path: '',
        redirect: '/tender/projects'
      },
      {
        path: 'projects',
        name: 'TenderProjects',
        component: TenderList,
        // 每条子路由都单独带上守卫元信息：vue-router 3 的 to.meta 取的是最后一条 matched 的 meta，
        // 只在父路由上写 role 会让 /tender/projects 直接绕过角色校验。
        meta: { requiresAuth: true, role: 'tender' }
      },
      {
        path: 'projects/new',
        name: 'TenderCreate',
        component: TenderForm,
        meta: { requiresAuth: true, role: 'tender' }
      }
    ]
  },
  {
    path: '/admin',
    name: 'AdminDashboard',
    component: AdminDashboard,
    meta: { requiresAuth: true, role: 'admin' }
  }
]

const router = new VueRouter({
  mode: 'history',
  base: process.env.BASE_URL,
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const loggedIn = localStorage.getItem('user')
  let user = null
  if (loggedIn) {
    try {
      user = JSON.parse(loggedIn)
    } catch (e) {
      // 清理无效的 user 值，避免解析异常造成导航中断
      localStorage.removeItem('user')
    }
  }
  
  // 检查是否需要认证
  if (to.matched.some(record => record.meta.requiresAuth)) {
    if (!user) {
      next('/login')
      return
    }
    
    // 检查角色权限
    if (to.meta.role) {
      if (user.role !== to.meta.role) {
        next('/')
        return
      }
    }
  }
  
  next()
})

export default router
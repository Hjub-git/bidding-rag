import Vue from 'vue'
import Vuex from 'vuex'
import { userApi, tenderApi, bidApi, authApi } from '../services/api'

Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    user: (() => {
      const userStr = localStorage.getItem('user')
      try {
        return userStr ? JSON.parse(userStr) : null
      } catch (e) {
        // 如果历史上存入了无效字符串，清理并回退为 null
        localStorage.removeItem('user')
        return null
      }
    })(),
    token: localStorage.getItem('token') || null,
    tenders: [],
    bids: [],
    loading: false,
    error: null
  },
  mutations: {
    SET_USER(state, user) {
      state.user = user
    },
    SET_TOKEN(state, token) {
      state.token = token
    },
    SET_TENDERS(state, tenders) {
      state.tenders = tenders
    },
    SET_BIDS(state, bids) {
      state.bids = bids
    },
    SET_LOADING(state, loading) {
      state.loading = loading
    },
    SET_ERROR(state, error) {
      state.error = error
    },
    CLEAR_ERROR(state) {
      state.error = null
    }
  },
  actions: {
    // 用户登录
    async login({ commit }, credentials) {
      commit('SET_LOADING', true)
      commit('CLEAR_ERROR')
      try {
        const response = await userApi.login(credentials)
        const { id, username, email, role, token } = response

        // 后端返回的是扁平字段，这里组装成 user 对象
        const user = { id, username, email, role }

        localStorage.setItem('user', JSON.stringify(user))
        localStorage.setItem('token', token)

        commit('SET_USER', user)
        commit('SET_TOKEN', token)
        commit('SET_LOADING', false)
        
        return user
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '登录失败')
        commit('SET_LOADING', false)
        throw error
      }
    },
    
    // 用户注册
    async register({ commit }, userData) {
      commit('SET_LOADING', true)
      commit('CLEAR_ERROR')
      try {
        const response = await userApi.register(userData)
        const { id, username, email, role, token } = response

        // 组装并持久化用户与令牌，注册后直接视为已登录
        const user = { id, username, email, role }
        localStorage.setItem('user', JSON.stringify(user))
        localStorage.setItem('token', token)

        commit('SET_USER', user)
        commit('SET_TOKEN', token)
        commit('SET_LOADING', false)
        return user
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '注册失败')
        commit('SET_LOADING', false)
        throw error
      }
    },
    
    // 退出登录（T19：先让后端撤销 token，再清本地）
    // 注意两点：
    // ① 调用方（各 Dashboard 的 logout）是 `dispatch('logout')` 后立刻 $router.push('/')，
    //    不 await。所以本地清理必须同步先做完，否则会出现"跳走了但还显示已登录"的中间态。
    // ② token 必须显式取出来再清：清完 localStorage，api.js 的请求拦截器就取不到 token 了，
    //    那样撤销请求会变成匿名请求（401）→ 根本没撤销成功。
    // 撤销失败一律忽略（断网 / token 已过期），本地已经退出，不能把用户卡在登录页。
    async logout({ commit }) {
      const token = localStorage.getItem('token')
      localStorage.removeItem('user')
      localStorage.removeItem('token')
      commit('SET_USER', null)
      commit('SET_TOKEN', null)
      if (token) {
        try {
          await authApi.logout(token)
        } catch (e) {
          // 忽略：撤销是尽力而为，本地已登出
        }
      }
    },
    
    // 获取招标项目列表
    async fetchTenders({ commit }, params) {
      commit('SET_LOADING', true)
      try {
        const tenders = await tenderApi.getTenders(params)
        commit('SET_TENDERS', tenders)
        commit('SET_LOADING', false)
        return tenders
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '获取招标项目失败')
        commit('SET_LOADING', false)
        throw error
      }
    },
    
    // 创建招标项目
    async createTender({ commit }, tenderData) {
      commit('SET_LOADING', true)
      try {
        const response = await tenderApi.createTender(tenderData)
        commit('SET_LOADING', false)
        return response
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '创建招标项目失败')
        commit('SET_LOADING', false)
        throw error
      }
    },
    
    // 获取投标列表
    async fetchBids({ commit }, params) {
      commit('SET_LOADING', true)
      try {
        const bids = await bidApi.getBids(params)
        commit('SET_BIDS', bids)
        commit('SET_LOADING', false)
        return bids
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '获取投标列表失败')
        commit('SET_LOADING', false)
        throw error
      }
    },
    
    // 创建投标
    async createBid({ commit }, bidData) {
      commit('SET_LOADING', true)
      try {
        const response = await bidApi.createBid(bidData)
        commit('SET_LOADING', false)
        return response
      } catch (error) {
        commit('SET_ERROR', error.response?.data?.message || '创建投标失败')
        commit('SET_LOADING', false)
        throw error
      }
    }
  },
  getters: {
    isAuthenticated: state => !!state.token,
    currentUser: state => state.user,
    userRole: state => state.user ? state.user.role : null,
    isAdmin: state => state.user && state.user.role === 'admin',
    isManufacturer: state => state.user && state.user.role === 'manufacturer',
    isTenderUnit: state => state.user && state.user.role === 'tender',
    allTenders: state => state.tenders,
    allBids: state => state.bids,
    isLoading: state => state.loading,
    error: state => state.error
  }
})
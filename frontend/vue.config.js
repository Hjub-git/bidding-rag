// Vue CLI 配置
// 后端 Spring Boot 跑在 8080，前端开发服务器改到 8081 避免端口冲突；
// 前端请求 /api/** 由 dev server 代理到后端，规避浏览器跨域。
module.exports = {
  devServer: {
    port: 8081,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
}

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // 秒杀相关API保留/api前缀
      '/api/addSeckillProduct': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/startSeckill': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/miaoshalist': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api/result': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 其他API去掉/api前缀
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})

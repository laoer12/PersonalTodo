import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

// 开发时复用已经运行的 Java 后台。正式版的资源直接打包进 JAR，不需要 Vite。
export default defineConfig(() => {
  const infoPath = path.join(root, 'runtime', 'server.json')
  let proxy = {}
  if (fs.existsSync(infoPath)) {
    const { origin, token } = JSON.parse(fs.readFileSync(infoPath, 'utf8'))
    proxy = { '/api': { target: origin, changeOrigin: true, configure(server) {
      server.on('proxyReq', req => { req.setHeader('Authorization', `Bearer ${token}`); req.setHeader('Origin', origin) })
    } } }
  }
  return { plugins: [vue()], server: { host: '127.0.0.1', proxy }, build: { outDir: 'dist', emptyOutDir: true } }
})

/**
 * 医养接力站前端静态服务：托管 frontend/dist，并把 /api/** 代理到后端 8081。
 *
 * 为什么演示用静态服务而不是 vite dev：
 * 比赛录屏时不需要热更新，静态服务更稳；也不会因为监听临时目录（导出/截图）而抖动。
 *
 * 用法：node scripts/serve-frontend.mjs                （默认 http://127.0.0.1:4173）
 *       PORT=8088 node scripts/serve-frontend.mjs
 * 需要先 npm run build 产出 frontend/dist。
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(process.env.FRONTEND_DIST || path.join(HERE, '..', 'frontend', 'dist'))
const API = process.env.API_TARGET || 'http://127.0.0.1:8081'
const PORT = Number(process.env.PORT || 4173)

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.ico': 'image/x-icon',
}

async function proxy(req, res, url) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const body = chunks.length ? Buffer.concat(chunks) : undefined
  // 只转发业务相关的头：content-length / transfer-encoding / connection 交给 fetch 自己算，
  // 原样转发会让 undici 直接抛 "fetch failed"，表现为接口 502。
  const headers = {}
  for (const [key, value] of Object.entries(req.headers)) {
    if (
      ['host', 'content-length', 'transfer-encoding', 'connection', 'accept-encoding', 'expect'].includes(key)
    ) {
      continue
    }
    headers[key] = value
  }
  headers.host = '127.0.0.1:8081'
  try {
    const upstream = await fetch(API + url.pathname + url.search, {
      method: req.method,
      headers,
      body,
      redirect: 'manual',
    })
    const buffer = Buffer.from(await upstream.arrayBuffer())
    const out = {}
    upstream.headers.forEach((value, key) => {
      if (!['content-encoding', 'transfer-encoding', 'connection', 'content-length'].includes(key)) {
        out[key] = value
      }
    })
    res.writeHead(upstream.status, out)
    res.end(buffer)
  } catch {
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ code: 502, message: '后端服务未启动，请先运行 backend 再刷新页面' }))
  }
}

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${PORT}`)

    if (url.pathname.startsWith('/api/')) {
      await proxy(req, res, url)
      return
    }

    let file = path.join(ROOT, decodeURIComponent(url.pathname))
    if (!file.startsWith(ROOT)) {
      res.writeHead(403)
      res.end('forbidden')
      return
    }
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      file = path.join(ROOT, 'index.html')
    }

    const ext = path.extname(file).toLowerCase()
    res.writeHead(200, {
      'content-type': TYPES[ext] || 'application/octet-stream',
      'cache-control': ext === '.html' ? 'no-cache' : 'public, max-age=31536000, immutable',
    })
    fs.createReadStream(file).pipe(res)
  })
  .listen(PORT, '127.0.0.1', () => {
    console.log(`医养接力站前端已启动：http://127.0.0.1:${PORT}`)
    console.log(`静态目录：${ROOT}`)
    console.log(`接口代理：/api/** -> ${API}`)
  })

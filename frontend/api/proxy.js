const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'content-encoding',
  'content-length',
  'host',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

function normalizeApiBase(value) {
  const trimmed = (value || '').trim().replace(/\/+$/, '')
  if (!trimmed) {
    return ''
  }
  return trimmed.endsWith('/api') ? trimmed : `${trimmed}/api`
}

function resolveApiBase() {
  return normalizeApiBase(
    process.env.BACKEND_API_BASE ||
      process.env.VITE_API_BASE ||
      process.env.RAILWAY_BACKEND_URL ||
      process.env.BACKEND_URL
  )
}

function readRequestBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

function copyRequestHeaders(headers) {
  const result = {}
  for (const [name, value] of Object.entries(headers)) {
    const normalizedName = name.toLowerCase()
    if (!HOP_BY_HOP_HEADERS.has(normalizedName) && normalizedName !== 'accept-encoding' && value !== undefined) {
      result[name] = value
    }
  }
  return result
}

function copyResponseHeaders(response, res) {
  response.headers.forEach((value, name) => {
    if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) {
      res.setHeader(name, value)
    }
  })
}

function firstQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

export default async function handler(req, res) {
  const apiBase = resolveApiBase()
  if (!apiBase) {
    res.status(502).json({
      code: 1,
      message: 'Backend API base URL is not configured. Set BACKEND_API_BASE or VITE_API_BASE in Vercel.',
      data: null,
    })
    return
  }

  const incomingUrl = new URL(req.url || '/', 'https://vercel.local')
  const proxyPath = firstQueryValue(req.query?.path) || incomingUrl.searchParams.get('path') || ''
  incomingUrl.searchParams.delete('path')
  const query = incomingUrl.searchParams.toString()
  const targetUrl = `${apiBase}/${String(proxyPath).replace(/^\/+/, '')}${query ? `?${query}` : ''}`

  try {
    const method = req.method || 'GET'
    const body = method === 'GET' || method === 'HEAD' ? undefined : await readRequestBody(req)
    const response = await fetch(targetUrl, {
      method,
      headers: copyRequestHeaders(req.headers),
      body,
      redirect: 'manual',
    })

    copyResponseHeaders(response, res)
    res.status(response.status)

    const buffer = Buffer.from(await response.arrayBuffer())
    res.send(buffer)
  } catch (error) {
    res.status(502).json({
      code: 1,
      message: `Unable to reach backend API: ${error instanceof Error ? error.message : 'unknown error'}`,
      data: null,
    })
  }
}

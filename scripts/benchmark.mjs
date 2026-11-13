#!/usr/bin/env node
/**
 * LoadSense Engine & Realtime Dashboard SLA Benchmark
 *
 * Exercises 50, 100, and 300 VUs against /demo/echo (or TARGET_URL),
 * subscribes to private STOMP execution updates, and measures
 * realtime update latency (receivedAt - metric.sampleTime) against the < 1-2s SLA.
 *
 * Environment variables:
 *   BASE_URL          Core Engine base URL (default: http://localhost:8080)
 *   TARGET_URL        Target URL to benchmark (default: ${BASE_URL}/demo/echo?delayMs=20)
 *   VUS_LIST          Comma-separated VU counts (default: 50,100,300)
 *   DURATION_SECONDS  Duration per run in seconds (default: 15)
 *   RPS_LIMIT         Shared RPS cap per run (default: 500)
 *   TIMEOUT_MS        Request timeout in ms (default: 5000)
 *   SLA_MS            Max allowed p95 realtime update latency in ms (default: 2000)
 *   STRICT_SLA        Set to "1" to exit non-zero if SLA_MS is exceeded
 *   BENCH_USER        Optional existing username (otherwise registers a fresh user)
 *   BENCH_PASS        Optional password for BENCH_USER (default: bench-test-password-123)
 */

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '')
const TARGET_URL = process.env.TARGET_URL || `${BASE_URL}/demo/echo?delayMs=20`
const VUS_LIST = (process.env.VUS_LIST || '50,100,300')
  .split(',')
  .map(v => Number.parseInt(v.trim(), 10))
  .filter(v => Number.isFinite(v) && v > 0)
const DURATION_SECONDS = Number.parseInt(process.env.DURATION_SECONDS || '15', 10)
const RPS_LIMIT = Number.parseInt(process.env.RPS_LIMIT || '500', 10)
const TIMEOUT_MS = Number.parseInt(process.env.TIMEOUT_MS || '5000', 10)
const SLA_MS = Number.parseInt(process.env.SLA_MS || '2000', 10)
const STRICT_SLA = process.env.STRICT_SLA === '1'
const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'STOPPED'])

function wsUrlFromBase(baseUrl) {
  return baseUrl.replace(/^http/, 'ws') + '/ws'
}

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!res.ok) {
    throw new Error(`${method} ${path} failed (${res.status}): ${typeof data === 'string' ? data : JSON.stringify(data)}`)
  }
  return data
}

async function authenticate() {
  const username = process.env.BENCH_USER || `bench_${Date.now()}`
  const password = process.env.BENCH_PASS || 'bench-test-password-123'
  if (process.env.BENCH_USER) {
    const res = await api('/api/auth/login', { method: 'POST', body: { username, password } })
    return { username, token: res.token }
  }
  const res = await api('/api/auth/register', { method: 'POST', body: { username, password } })
  return { username, token: res.token }
}

function buildStompFrame(command, headers = {}, body = '') {
  const lines = [command]
  for (const [k, v] of Object.entries(headers)) {
    lines.push(`${k}:${v}`)
  }
  lines.push('', body)
  return lines.join('\n') + '\0'
}

function parseStompFrames(raw) {
  const frames = []
  const chunks = raw.split('\0')
  for (const chunk of chunks) {
    const trimmed = chunk.replace(/^\n+/, '')
    if (!trimmed) continue
    const sep = trimmed.indexOf('\n\n')
    const headerPart = sep >= 0 ? trimmed.slice(0, sep) : trimmed
    const body = sep >= 0 ? trimmed.slice(sep + 2) : ''
    const lines = headerPart.split('\n')
    const command = lines[0]?.trim()
    if (!command) continue
    const headers = {}
    for (let i = 1; i < lines.length; i++) {
      const idx = lines[i].indexOf(':')
      if (idx > 0) headers[lines[i].slice(0, idx)] = lines[i].slice(idx + 1)
    }
    frames.push({ command, headers, body })
  }
  return frames
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[Math.max(0, idx)]
}

async function runSingleBenchmark(token, vus) {
  const config = await api('/api/test-configs', {
    method: 'POST',
    token,
    body: {
      name: `Benchmark ${vus} VUs`,
      targetUrl: TARGET_URL,
      httpMethod: 'GET',
      headers: {},
      body: '',
      virtualUsers: vus,
      durationSeconds: DURATION_SECONDS,
      requestsPerSecond: RPS_LIMIT,
      timeoutMs: TIMEOUT_MS,
    },
  })

  const execution = await api(`/api/test-configs/${config.id}/executions`, {
    method: 'POST',
    token,
    body: { authorizedTarget: true, mockDataSetId: null, autoAiAnalyze: false },
  })

  const latenciesMs = []
  let finalExecution = execution

  await new Promise((resolve, reject) => {
    let settled = false
    let pollTimer = null
    const timeoutTimer = setTimeout(() => {
      cleanup()
      reject(new Error(`Execution ${execution.id} (${vus} VUs) timed out`))
    }, (DURATION_SECONDS + 30) * 1000)

    const ws = new WebSocket(wsUrlFromBase(BASE_URL))

    const cleanup = () => {
      clearTimeout(timeoutTimer)
      clearTimeout(pollTimer)
      try {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(buildStompFrame('DISCONNECT', {}))
        }
        ws.close()
      } catch {
        // ignore close errors
      }
    }

    const finish = execView => {
      if (settled) return
      settled = true
      finalExecution = execView
      cleanup()
      resolve()
    }

    const pollStatus = async () => {
      if (settled) return
      try {
        const current = await api(`/api/executions/${execution.id}`, { token })
        if (TERMINAL_STATES.has(current.status)) {
          finish(current)
          return
        }
      } catch {
        // transient poll error; retry on next tick
      }
      if (!settled) pollTimer = setTimeout(pollStatus, 2000)
    }

    ws.addEventListener('open', () => {
      ws.send(buildStompFrame('CONNECT', {
        'accept-version': '1.2,1.1,1.0',
        'heart-beat': '0,0',
        Authorization: `Bearer ${token}`,
      }))
    })

    ws.addEventListener('message', event => {
      const receivedAt = Date.now()
      const text = typeof event.data === 'string' ? event.data : String(event.data)
      for (const frame of parseStompFrames(text)) {
        if (frame.command === 'CONNECTED') {
          ws.send(buildStompFrame('SUBSCRIBE', {
            id: `sub-${execution.id}`,
            destination: `/user/queue/executions/${execution.id}`,
          }))
        } else if (frame.command === 'MESSAGE' && frame.body) {
          try {
            const payload = JSON.parse(frame.body)
            if (payload.metric?.sampleTime) {
              const sampleMs = new Date(payload.metric.sampleTime).getTime()
              if (Number.isFinite(sampleMs)) {
                latenciesMs.push(Math.max(0, receivedAt - sampleMs))
              }
            }
            if (payload.execution && TERMINAL_STATES.has(payload.execution.status)) {
              finish(payload.execution)
            }
          } catch {
            // ignore malformed frame body
          }
        } else if (frame.command === 'ERROR') {
          // Fallback to polling if STOMP reports an error
          if (!pollTimer) pollTimer = setTimeout(pollStatus, 500)
        }
      }
    })

    ws.addEventListener('error', () => {
      if (!pollTimer && !settled) pollTimer = setTimeout(pollStatus, 500)
    })

    pollTimer = setTimeout(pollStatus, 2000)
  })

  const sorted = [...latenciesMs].sort((a, b) => a - b)
  const avgUpdateMs = sorted.length
    ? Math.round(sorted.reduce((sum, v) => sum + v, 0) / sorted.length)
    : null
  const p95UpdateMs = sorted.length ? percentile(sorted, 95) : null
  const maxUpdateMs = sorted.length ? sorted.at(-1) : null
  const slaPass = finalExecution.status === 'COMPLETED' && (p95UpdateMs === null || p95UpdateMs <= SLA_MS)

  return {
    vus,
    executionId: finalExecution.id,
    status: finalExecution.status,
    totalRequests: finalExecution.totalRequests,
    failedRequests: finalExecution.failedRequests,
    throughput: Number(finalExecution.throughput?.toFixed(2) ?? 0),
    p50LatencyMs: Number(finalExecution.p50LatencyMs?.toFixed(1) ?? 0),
    p95LatencyMs: Number(finalExecution.p95LatencyMs?.toFixed(1) ?? 0),
    p99LatencyMs: Number(finalExecution.p99LatencyMs?.toFixed(1) ?? 0),
    errorRate: Number(finalExecution.errorRate?.toFixed(2) ?? 0),
    stompSamples: sorted.length,
    avgUpdateMs,
    p95UpdateMs,
    maxUpdateMs,
    slaPass,
  }
}

async function main() {
  console.log(`LoadSense Benchmark | BASE_URL=${BASE_URL} | TARGET_URL=${TARGET_URL} | VUs=${VUS_LIST.join(',')} | DURATION=${DURATION_SECONDS}s | SLA<=${SLA_MS}ms`)
  const { username, token } = await authenticate()
  console.log(`Authenticated as ${username}`)

  const results = []
  for (const vus of VUS_LIST) {
    console.log(`\nRunning ${vus} VUs for ${DURATION_SECONDS}s...`)
    const result = await runSingleBenchmark(token, vus)
    results.push(result)
    console.log(JSON.stringify(result))
  }

  console.log('\n=== Benchmark Summary ===')
  console.table(results)

  const failed = results.filter(r => !r.slaPass)
  if (failed.length > 0) {
    console.error(`Benchmark warning: ${failed.length} run(s) failed completion or exceeded ${SLA_MS}ms realtime SLA.`)
    if (STRICT_SLA) process.exit(1)
  }
}

main().catch(err => {
  console.error(err)
  process.exit(1)
})

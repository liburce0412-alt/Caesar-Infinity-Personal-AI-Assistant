// Temporary device acceptance relay. Production server/configuration are untouched.
import { createServer } from 'node:http'
import { appendFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createRelay, MODEL, VISION_MODEL, CHAT_PATH } from '../../apps/cloud-free/relay.mjs'
import { TOOL_NAMES, wireName } from '../../apps/cloud-free/tools.mjs'

const auth = new URL(process.env.SUPABASE_URL ?? '')
if (auth.origin !== 'https://campusai.campus3ai.xyz' || !process.env.SUPABASE_ANON_KEY || !process.env.ZHIPU_API_KEY) {
  throw new Error('Expected existing authentication configuration and provider credentials')
}
const authEndpoint = `${auth.origin}/auth/v1/user`
const providerEndpoint = 'https://open.bigmodel.cn/api/paas/v4/chat/completions'
const models = new Set([MODEL, VISION_MODEL])
const toolNames = new Set(TOOL_NAMES.map(wireName))
function recordSummaryChecks(messages) {
  return messages.filter(message => message.role === 'tool' && message.name === 'time_list_records')
    .map(message => {
      try {
        const result = JSON.parse(message.content)
        const summary = result.summary
        if (summary?.scope !== 'returned_records' || !Number.isSafeInteger(summary.recordCount) ||
            summary.recordCount < 0 || summary.recordCount > 50 ||
            !Number.isSafeInteger(summary.totalDurationMinutes) || !Array.isArray(result.data)) return { present: false }
        return { present: true, count: summary.recordCount, minutes: summary.totalDurationMinutes,
          matchesData: summary.recordCount === result.data.length &&
            summary.totalDurationMinutes === result.data.reduce((sum, record) => sum + record.durationMinutes, 0) }
      } catch { return { present: false } }
    })
}
const logPath = fileURLToPath(new URL('../../artifacts/caesar-3.0-local-relay.jsonl', import.meta.url))
const log = event => {
  const line = JSON.stringify({ at: new Date().toISOString(), ...event })
  appendFileSync(logPath, `${line}\n`)
  console.log(line)
}
let upstreamRequests = 0
let appRequests = 0
const relay = createRelay({
  supabaseUrl: auth.origin,
  anonKey: process.env.SUPABASE_ANON_KEY,
  apiKey: process.env.ZHIPU_API_KEY,
  concurrency: 1,
  diagnostic: log,
  fetchImpl: async (url, options) => {
    if (url !== authEndpoint && url !== providerEndpoint) throw new Error('Unexpected relay destination')
    const start = Date.now()
    let metadata = { kind: 'auth' }
    if (url === providerEndpoint) {
      // Inspect only fixed names/counts in memory; never persist messages or results.
      const body = JSON.parse(options.body)
      if (!models.has(body.model)) throw new Error('Unexpected model')
      const messages = body.messages ?? []
      metadata = {
        kind: 'provider', sequence: ++upstreamRequests, model: body.model,
        replayTools: messages.filter(message => message.role === 'tool')
          .map(message => toolNames.has(message.name) ? message.name : 'unknown'),
        recordSummaryChecks: recordSummaryChecks(messages),
      }
    }
    try {
      const response = await fetch(url, options)
      log({ event: 'upstream_headers', ...metadata, status: response.status, elapsedMs: Date.now() - start })
      return response
    } catch (error) {
      log({ event: 'upstream_network_error', ...metadata, elapsedMs: Date.now() - start })
      throw error
    }
  },
})
const server = createServer((request, response) => {
  const start = Date.now()
  const sequence = request.method === 'POST' && request.url === CHAT_PATH ? ++appRequests : null
  response.once('finish', () => log({ event: 'http_finish', sequence, status: response.statusCode, elapsedMs: Date.now() - start }))
  relay(request, response)
})
server.headersTimeout = 10_000
server.requestTimeout = 15_000
server.maxHeadersCount = 32
server.listen(18789, '127.0.0.1', () => log({ event: 'local_relay_ready', port: 18789 }))
const stop = () => {
  log({ event: 'local_relay_stopping', appRequests, upstreamRequests })
  server.close()
  server.closeIdleConnections()
  setTimeout(() => process.exit(0), 130_000).unref()
}
process.once('SIGINT', stop)
process.once('SIGTERM', stop)

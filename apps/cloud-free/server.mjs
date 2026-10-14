import { createServer } from 'node:http'
import { createRelay } from './relay.mjs'

const server = createServer(createRelay({
  supabaseUrl: process.env.CAESAR_AUTH_URL ?? 'http://127.0.0.1:8000',
  anonKey: process.env.SUPABASE_ANON_KEY,
  apiKey: process.env.ZHIPU_API_KEY,
  concurrency: Number(process.env.CAESAR_CLOUD_CONCURRENCY ?? 1),
}))
server.headersTimeout = 10_000
server.requestTimeout = 15_000
server.maxHeadersCount = 32
server.listen(8788, '127.0.0.1', () => console.log('Caesar cloud free listening on loopback:8788'))
const stop = () => { server.close(); setTimeout(() => process.exit(0), 130_000).unref() }
process.once('SIGTERM', stop)
process.once('SIGINT', stop)

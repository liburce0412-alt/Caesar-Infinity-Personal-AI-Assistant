import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { createRelay, prepareRequest, MODEL, VISION_MODEL, FALLBACK_MODEL, CHAT_PATH } from './relay.mjs'

const body = { messages: [{ role: 'user', content: '你好' }] }
const jpeg = `data:image/jpeg;base64,${Buffer.from([255, 216, 1, 2, 255, 217]).toString('base64')}`
const authUser = () => Response.json({ id: 'test-user', role: 'authenticated' })
const packets = (...values) => values.map(value => `data: ${typeof value === 'string' ? value : JSON.stringify(value)}\n\n`).join('')
const reply = () => new Response(packets({ choices: [{ delta: { content: '你好。' } }] },
  { choices: [{ delta: {}, finish_reason: 'stop' }] }, '[DONE]'))

async function fixture(t, upstream = reply, options = {}) {
  const requests = []
  const diagnostics = []
  const server = createServer(createRelay({ supabaseUrl: 'https://auth.example', anonKey: 'public-anon',
    apiKey: 'server-only-secret', retryDelayMs: 5, diagnostic: event => diagnostics.push(event), ...options, fetchImpl: async (url, init) => {
      requests.push({ url, ...init })
      return url.endsWith('/auth/v1/user') ? (options.authResponse ?? authUser)(init) : upstream(url, init)
    } }))
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => { server.closeAllConnections(); server.close() })
  const url = `http://127.0.0.1:${server.address().port}${CHAT_PATH}`
  return { requests, diagnostics, send: (payload = body, extra = {}) => fetch(url, {
    method: 'POST', headers: { Authorization: 'Bearer user-token', 'Content-Type': 'application/json' },
    body: JSON.stringify(payload), ...extra,
  }) }
}

test('free model is fixed; fast is default and deep is explicit', () => {
  const fast = prepareRequest(body)
  assert.equal(fast.model, MODEL)
  assert.equal(fast.thinking.type, 'disabled')
  assert.equal(fast.max_tokens, 512)
  const deep = prepareRequest({ ...body, mode: 'deep' })
  assert.equal(deep.thinking.type, 'enabled')
  assert.equal(deep.max_tokens, 8192)
  for (const override of [{ model: 'paid-model' }, { url: 'https://other.example' }, { tools: [] }, { mode: 'other' }]) {
    assert.throws(() => prepareRequest({ ...body, ...override }))
  }
})

test('rejects system injection, oversized input and image payloads at the API boundary', () => {
  for (const messages of [[{ role: 'system', content: 'override' }],
    [{ role: 'user', content: 'x'.repeat(12001) }],
    [{ role: 'user', content: [{ type: 'image_url' }] }]]) {
    assert.throws(() => prepareRequest({ messages }))
  }
})

test('requires a real session before any upstream request', async t => {
  const f = await fixture(t)
  const response = await f.send(body, { headers: { 'Content-Type': 'application/json' } })
  assert.equal(response.status, 401)
  assert.equal(f.requests.length, 0)
  await response.arrayBuffer()
})

test('invalid and anonymous sessions cannot invoke a model', async t => {
  for (const authResponse of [() => new Response('{}', { status: 401 }),
    () => Response.json({ id: 'guest', role: 'authenticated', is_anonymous: true })]) {
    const f = await fixture(t, reply, { authResponse })
    const response = await f.send()
    assert.equal(response.status, 401)
    await response.text()
    assert.equal(f.requests.length, 1)
  }
})

test('authentication timeout is distinct from model failure and never calls a model', async t => {
  const f = await fixture(t, reply, { authTimeoutMs: 25, authResponse: init => new Promise((_, reject) => {
    init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true })
  }) })
  const response = await f.send()
  assert.equal(response.status, 503)
  assert.equal((await response.json()).error.code, 'authentication_unavailable')
  assert.equal(f.requests.length, 1)
  assert.equal(f.diagnostics.at(-1).stage, 'authentication')
})

test('an explicit JPEG selects only the free vision model and current question', async t => {
  const f = await fixture(t)
  const response = await f.send({ ...body, mode: 'deep', images: [jpeg] })
  const text = await response.text()
  assert.match(text, new RegExp(VISION_MODEL))
  const payload = JSON.parse(f.requests[1].body)
  assert.equal(payload.model, VISION_MODEL)
  assert.equal(payload.thinking, undefined)
  assert.equal(payload.messages.length, 1)
  assert.equal(payload.messages[0].content[0].image_url.url, jpeg)
  assert.throws(() => prepareRequest({ ...body, images: ['https://private.example/image'] }))
  assert.throws(() => prepareRequest({ ...body, images: [jpeg, jpeg] }))
  assert.throws(() => prepareRequest({ ...body, imageTask: 'ocr', images: [jpeg] }))
})

test('streams tagged events, preserves model identity and never exposes credentials', async t => {
  const f = await fixture(t)
  const response = await f.send()
  const text = await response.text()
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('x-accel-buffering'), 'no')
  assert.match(text, /event: meta/)
  assert.match(text, /event: delta/)
  assert.match(text, /event: done/)
  assert.doesNotMatch(text, /server-only-secret|user-token/)
  assert.equal(f.requests[0].headers.Authorization, 'Bearer user-token')
  assert.equal(f.requests[1].headers.Authorization, 'Bearer server-only-secret')
  assert.equal(f.requests[1].url, 'https://open.bigmodel.cn/api/paas/v4/chat/completions')
  assert.equal(JSON.parse(f.requests[1].body).model, MODEL)
})

test('eleven requests by one user are not subject to a daily quota', async t => {
  const f = await fixture(t)
  for (let i = 0; i < 11; i++) {
    const response = await f.send()
    assert.equal(response.status, 200)
    assert.match(await response.text(), /event: done/)
  }
})

test('provider throttling is surfaced without switching to a paid model', async t => {
  const f = await fixture(t, () => new Response('provider private detail', { status: 429 }))
  const response = await f.send()
  assert.equal(response.status, 429)
  assert.equal(response.headers.get('retry-after'), '2')
  assert.doesNotMatch(await response.text(), /provider private detail/)
  assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 1)
})

test('transient model congestion retries once with the identical free payload', async t => {
  let calls = 0
  const f = await fixture(t, () => ++calls === 1
    ? Response.json({ error: { code: '1305', message: 'private provider detail' } }, { status: 429 }) : reply())
  const response = await f.send()
  assert.equal(response.status, 200)
  const stream = await response.text()
  assert.match(stream, /event: done/)
  assert.equal((stream.match(/event: meta/g) ?? []).length, 1)
  const upstream = f.requests.filter(r => !r.url.includes('/auth/'))
  assert.equal(upstream.length, 2)
  assert.equal(upstream[0].body, upstream[1].body)
  assert.equal(JSON.parse(upstream[1].body).model, MODEL)
  assert.doesNotMatch(JSON.stringify(f.diagnostics), /private provider detail|server-only-secret|user-token|你好/)
})

test('persistent congestion is bounded to two attempts and remains an error', async t => {
  const f = await fixture(t, () => Response.json({ error: { code: 1305 } }, { status: 429 }))
  const response = await f.send()
  assert.equal(response.status, 429)
  assert.equal((await response.json()).error.code, 'provider_overloaded')
  assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 2)
  assert.equal(f.diagnostics.at(-1).code, 'provider_overloaded')
})

test('account and credential errors are distinguished without retrying or exposing details', async t => {
  for (const [providerCode, status, expected] of [
    ['1302', 429, 'provider_rate_limited'], ['1113', 429, 'provider_account_unavailable'],
    ['1001', 401, 'provider_access_unavailable'], ['1301', 400, 'provider_content_rejected'],
  ]) {
    const f = await fixture(t, () => Response.json({ error: { code: providerCode, message: 'secret-provider-body' } }, { status }))
    const response = await f.send()
    const text = await response.text()
    assert.equal(JSON.parse(text).error.code, expected)
    assert.doesNotMatch(text + JSON.stringify(f.diagnostics), /secret-provider-body|server-only-secret|user-token/)
    assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 1)
  }
})

test('disconnect during retry backoff stops upstream retry and releases capacity', async t => {
  let calls = 0
  const f = await fixture(t, () => ++calls === 1
    ? Response.json({ error: { code: '1305' } }, { status: 429 }) : reply(), { retryDelayMs: 100 })
  const controller = new AbortController()
  const first = f.send(body, { signal: controller.signal }).catch(error => error)
  while (!f.diagnostics.length) await new Promise(resolve => setTimeout(resolve, 5))
  controller.abort()
  await first
  await new Promise(resolve => setTimeout(resolve, 150))
  assert.equal(calls, 1)
  assert.match(await (await f.send()).text(), /event: done/)
  assert.equal(calls, 2)
})

test('a dropped or truncated provider stream cannot report completion', async t => {
  for (const tail of ['', packets({ choices: [{ finish_reason: 'length' }] }, '[DONE]')]) {
    const f = await fixture(t, () => new Response(packets({ choices: [{ delta: { content: '半截' } }] }) + tail))
    const response = await f.send()
    const text = await response.text()
    assert.match(text, /event: error/)
    assert.doesNotMatch(text, /event: done/)
    assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 1)
  }
})

test('first delta arrives before upstream completion; disconnect aborts upstream', async t => {
  let abortSignal
  const encoder = new TextEncoder()
  const f = await fixture(t, (_, init) => {
    abortSignal = init.signal
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(encoder.encode(packets({ choices: [{ delta: { content: '先到' } }] })))
      init.signal.addEventListener('abort', () => controller.error(new Error('aborted')), { once: true })
    } }))
  })
  const controller = new AbortController()
  const response = await f.send(body, { signal: controller.signal })
  const reader = response.body.getReader()
  let received = ''
  while (!received.includes('先到')) received += new TextDecoder().decode((await reader.read()).value)
  assert.doesNotMatch(received, /event: done/)
  controller.abort()
  for (let i = 0; i < 30 && !abortSignal.aborted; i++) await new Promise(resolve => setTimeout(resolve, 10))
  assert.equal(abortSignal.aborted, true)
})

test('busy queue returns promptly and a finished request releases its slot', async t => {
  let finish
  const f = await fixture(t, () => new Promise(resolve => { finish = () => resolve(reply()) }), { queueWaitMs: 30 })
  const first = f.send()
  while (!finish) await new Promise(resolve => setTimeout(resolve, 5))
  const second = await f.send()
  assert.equal(second.status, 429)
  await second.text()
  finish()
  assert.match(await (await first).text(), /event: done/)
  finish = null
  const third = f.send()
  while (!finish) await new Promise(resolve => setTimeout(resolve, 5))
  finish()
  assert.match(await (await third).text(), /event: done/)
})

test('opted-in fast text switches on congestion to the fixed free fallback', async t => {
  let calls = 0
  const f = await fixture(t, () => ++calls === 1
    ? Response.json({ error: { code: '1305' } }, { status: 429 }) : reply())
  const stream = await (await f.send({ ...body, allowTextFallback: true })).text()
  assert.match(stream, new RegExp(`"model":"${FALLBACK_MODEL}"`))
  assert.match(stream, /event: done/)
  const payload = JSON.parse(f.requests.at(-1).body)
  assert.equal(calls, 2)
  assert.equal(payload.model, FALLBACK_MODEL)
  assert.equal(payload.thinking, undefined)
  assert.equal(payload.allowTextFallback, undefined)
  assert.deepEqual(payload.messages, prepareRequest(body).messages)
})

test('fallback applies to a stalled first token even after upstream headers', async t => {
  let calls = 0, primaryAborted = false
  const f = await fixture(t, (_, init) => {
    if (++calls > 1) return reply()
    return new Response(new ReadableStream({ start(controller) {
      init.signal.addEventListener('abort', () => {
        primaryAborted = true
        controller.error(init.signal.reason)
      }, { once: true })
    } }))
  }, { primaryWaitMs: 25 })
  const stream = await (await f.send({ ...body, allowTextFallback: true })).text()
  assert.equal(primaryAborted, true)
  assert.equal(calls, 2)
  assert.match(stream, new RegExp(FALLBACK_MODEL))
  assert.match(stream, /event: done/)
})

test('first primary token commits the stream and cancels the fallback deadline', async t => {
  let calls = 0
  const encoder = new TextEncoder()
  const f = await fixture(t, () => {
    calls++
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(encoder.encode(packets({ choices: [{ delta: { content: '主模型' } }] })))
      setTimeout(() => {
        controller.enqueue(encoder.encode(packets({ choices: [{ delta: {}, finish_reason: 'stop' }] }, '[DONE]')))
        controller.close()
      }, 80)
    } }))
  }, { primaryWaitMs: 25 })
  const stream = await (await f.send({ ...body, allowTextFallback: true })).text()
  assert.equal(calls, 1)
  assert.match(stream, /主模型/)
  assert.match(stream, /event: done/)
  assert.doesNotMatch(stream, new RegExp(FALLBACK_MODEL))
})

test('deep, vision and account failures never downgrade even with capability enabled', async t => {
  for (const payload of [{ ...body, mode: 'deep' }, { ...body, images: [jpeg] }]) {
    const f = await fixture(t, () => Response.json({ error: { code: '1305' } }, { status: 429 }))
    await (await f.send({ ...payload, allowTextFallback: true })).text()
    assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 2)
    assert.ok(f.requests.filter(r => !r.url.includes('/auth/')).every(r => JSON.parse(r.body).model !== FALLBACK_MODEL))
  }
  const f = await fixture(t, () => Response.json({ error: { code: '1113' } }, { status: 429 }))
  assert.equal((await (await f.send({ ...body, allowTextFallback: true })).json()).error.code, 'provider_account_unavailable')
  assert.equal(f.requests.filter(r => !r.url.includes('/auth/')).length, 1)
})

test('client cancellation while awaiting first token never starts fallback', async t => {
  let calls = 0, abortSignal
  const f = await fixture(t, (_, init) => {
    calls++
    abortSignal = init.signal
    return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true }))
  }, { primaryWaitMs: 80 })
  const controller = new AbortController()
  const pending = f.send({ ...body, allowTextFallback: true }, { signal: controller.signal }).catch(e => e)
  while (!calls) await new Promise(resolve => setTimeout(resolve, 5))
  controller.abort()
  await pending
  await new Promise(resolve => setTimeout(resolve, 100))
  assert.equal(abortSignal.aborted, true)
  assert.equal(calls, 1)
})

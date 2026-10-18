import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { createRelay, prepareRequest, beijingClock, CHAT_PATH, MODEL } from './relay.mjs'
import { TOOL_NAMES } from './tools.mjs'

const request = { messages: [{ role: 'user', content: '搜索公开资料' }], toolProtocol: 1, tools: ['web.search'] }
const call = { id: 'call-1', type: 'function', function: { name: 'web_search', arguments: '{"query":"今日天气"}' } }
const replay = [request.messages[0], { role: 'assistant', content: '', tool_calls: [call] },
  { role: 'tool', name: 'web_search', tool_call_id: 'call-1', content: '{"ok":true,"data":{"results":[]}}' }]
const packet = value => `data: ${typeof value === 'string' ? value : JSON.stringify(value)}\n\n`
const toolPackets = () => [
  { choices: [{ delta: { tool_calls: [{ index: 0, id: 'call-1', type: 'function', function: { name: 'web_search', arguments: '{"query":' } }] } }] },
  { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"今日天气"}' } }] } }] },
  { choices: [{ delta: {}, finish_reason: 'tool_calls' }] }, '[DONE]',
]
const answer = () => new Response([{ choices: [{ delta: { content: '根据实际结果回答' }, finish_reason: 'stop' }] }, '[DONE]'].map(packet).join(''))
const events = text => text.split('\n').filter(line => line.startsWith('data:')).map(line => JSON.parse(line.slice(5)))

async function fixture(t, upstream, options = {}) {
  const calls = []
  const server = createServer(createRelay({ supabaseUrl: 'https://auth.example', anonKey: 'public-anon', apiKey: 'synthetic-key',
    diagnostic: () => {}, retryDelayMs: 1, ...options, fetchImpl: async (url, init) => {
      if (url.endsWith('/auth/v1/user')) return Response.json({ id: 'test-user', role: 'authenticated' })
      calls.push(JSON.parse(init.body))
      return upstream(init, calls.length)
    } }))
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => { server.closeAllConnections(); server.close() })
  return { calls, send: (body = request, signal) => fetch(`http://127.0.0.1:${server.address().port}${CHAT_PATH}`, {
    method: 'POST', headers: { Authorization: 'Bearer synthetic-user', 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal,
  }) }
}

test('server clock is Beijing for each text and image request, independent of client context', () => {
  const now = Date.parse('2026-10-17T16:01:02Z')
  assert.match(beijingClock(now), /2026-10-18 00:01:02 \+08:00/)
  const first = prepareRequest({ ...request, context: { clock: 'old date' } }, now)
  const next = prepareRequest(request, now + 1000)
  assert.ok(first.messages.some(message => message.content.includes('2026-10-18 00:01:02')))
  assert.ok(next.messages.some(message => message.content.includes('2026-10-18 00:01:03')))
  const jpeg = `data:image/jpeg;base64,${Buffer.from([255, 216, 1, 255, 217]).toString('base64')}`
  const image = prepareRequest({ messages: [{ role: 'user', content: '查看图片' }], images: [jpeg], context: { health: 'private' } }, now)
  assert.match(image.messages[0].content, /2026-10-18 00:01:02/)
  assert.equal(image.messages.length, 2)
  assert.doesNotMatch(JSON.stringify(image), /private|private_context/)
})

test('protocol selects only server-owned schemas and never health, messaging or arbitrary URLs', () => {
  const prepared = prepareRequest(request)
  assert.equal(prepared.tools[0].function.name, 'web_search')
  assert.equal(prepared.tools[0].function.parameters.additionalProperties, false)
  assert.equal(prepared.parallel_tool_calls, false)
  for (const patch of [{ toolProtocol: 2 }, { tools: ['health.get_snapshot'] }, { tools: ['memory.list'] }, { tools: ['message.send'] },
    { tools: ['web.open'] }, { tools: ['web.search', 'web.search'] }, { tools: [{ name: 'web.search' }] },
    { tools: ['web.search'], images: ['data:image/jpeg;base64,/9gB/9k='] }]) {
    assert.throws(() => prepareRequest({ ...request, ...patch }))
  }
  assert.equal(prepareRequest({ ...request, tools: TOOL_NAMES }).tools.length, TOOL_NAMES.length)
})

test('current tool pairs roundtrip with call identity and result text preserved', () => {
  const prepared = prepareRequest({ ...request, messages: replay })
  assert.deepEqual(prepared.messages.slice(-3), replay)
  assert.equal(prepareRequest({ ...request, tools: [], messages: replay }).tools, undefined)
})

test('malformed, historical, parallel and unbounded tool replays are rejected', () => {
  const copy = () => structuredClone(replay)
  const bad = []
  let value = copy(); value[2].tool_call_id = 'other'; bad.push(value)
  value = copy(); value[1].tool_calls.push(call); bad.push(value)
  value = copy(); value[1].tool_calls[0].function.name = 'health_get_snapshot'; bad.push(value)
  value = copy(); value[1].tool_calls[0].function.name = 'memory_list'; bad.push(value)
  value = copy(); value[1].tool_calls[0].function.arguments = '{"query":"test","url":"http://private"}'; bad.push(value)
  value = copy(); value[1].tool_calls[0].function.arguments = '{"query":"test","maxResults":99}'; bad.push(value)
  value = copy(); value[2].content = 'x'.repeat(8001); bad.push(value)
  value = copy(); value.push({ role: 'user', content: 'new turn' }); bad.push(value)
  bad.push(replay.slice(0, 2))
  for (const messages of bad) assert.throws(() => prepareRequest({ ...request, messages }))
})

test('legacy free-text memory context is removed without classifying its content', () => {
  const prepared = prepareRequest({ ...request, context: {
    confirmedMemories: [{ content: 'night oxygen 88%; heart rate 145' }],
    nested: { confirmed_memories: [{ content: 'ordinary private preference' }], studyMinutes: 20 },
  } })
  const encoded = JSON.stringify(prepared)
  assert.doesNotMatch(encoded, /oxygen|145|private preference|confirmedMemories|confirmed_memories/)
  assert.match(encoded, /studyMinutes/)
  assert.ok(!TOOL_NAMES.includes('memory.list'))
  assert.ok(TOOL_NAMES.includes('memory.propose'))
})

test('fragmented real tool_calls become one complete validated event and can return a final answer', async t => {
  const f = await fixture(t, (_, index) => index === 1 ? new Response(toolPackets().map(packet).join('')) : answer())
  const first = events(await (await f.send()).text())
  assert.equal(first[0].toolProtocol, 1)
  assert.deepEqual(first.find(event => event.type === 'tool_call'), {
    type: 'tool_call', id: 'call-1', name: 'web.search', wireName: 'web_search', arguments: '{"query":"今日天气"}',
  })
  assert.equal(first.at(-1).type, 'done')
  const next = events(await (await f.send({ ...request, messages: replay })).text())
  assert.equal(next.at(-1).type, 'done')
  assert.equal(f.calls[1].messages.at(-1).tool_call_id, 'call-1')
})

test('partial, invalid, parallel and wrong-finish calls never produce an executable event', async t => {
  const variants = [toolPackets().slice(0, 1),
    [toolPackets()[0], { choices: [{ delta: {}, finish_reason: 'stop' }] }, '[DONE]'],
    [{ choices: [{ delta: { tool_calls: [{ ...call, index: 0 }, { ...call, id: 'call-2', index: 1 }] }, finish_reason: 'tool_calls' }] }, '[DONE]'],
    [{ choices: [{ delta: { tool_calls: [{ ...call, index: 0, function: { name: 'web_open', arguments: '{}' } }] }, finish_reason: 'tool_calls' }] }, '[DONE]'],
    [{ choices: [{ delta: { tool_calls: [{ ...call, index: 0, function: { name: 'web_search', arguments: '{"query":2}' } }] }, finish_reason: 'tool_calls' }] }, '[DONE]']]
  for (const values of variants) {
    const f = await fixture(t, () => new Response(values.map(packet).join('')))
    const output = events(await (await f.send()).text())
    assert.equal(output.some(event => event.type === 'tool_call'), false)
    assert.equal(output.at(-1).type, 'error')
  }
})

test('identical empty terminal events produce one tool call and one completed answer', async t => {
  const toolValues = [...toolPackets().slice(0, -1),
    { choices: [{ delta: { content: '', tool_calls: [] }, finish_reason: 'tool_calls' }] }, '[DONE]']
  const answerValues = [
    { choices: [{ delta: { content: '根据实际结果回答' }, finish_reason: 'stop' }] },
    { choices: [{ delta: {}, finish_reason: 'stop' }] }, '[DONE]',
  ]
  const f = await fixture(t, (_init, index) => new Response((index === 1 ? toolValues : answerValues).map(packet).join('')))
  const first = events(await (await f.send()).text())
  assert.equal(first.filter(event => event.type === 'tool_call').length, 1)
  assert.equal(first.filter(event => event.type === 'done').length, 1)
  const second = events(await (await f.send({ ...request, messages: replay })).text())
  assert.equal(second.filter(event => event.type === 'done').length, 1)
  assert.equal(second.some(event => event.type === 'error'), false)
  assert.equal(f.calls.length, 2)
})

test('conflicting terminal reasons and any new terminal payload cannot execute a tool', async t => {
  const terminals = [
    { choices: [{ delta: {}, finish_reason: 'stop' }] },
    { choices: [{ delta: { content: 'new content' }, finish_reason: 'tool_calls' }] },
    { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: 'new parameters' } }] }, finish_reason: 'tool_calls' }] },
    { choices: [{ delta: { reasoning_content: 'new reasoning' }, finish_reason: 'tool_calls' }] },
  ]
  for (const terminal of terminals) {
    const f = await fixture(t, () => new Response([...toolPackets().slice(0, -1), terminal, '[DONE]'].map(packet).join('')))
    const output = events(await (await f.send()).text())
    assert.equal(output.some(event => event.type === 'tool_call'), false)
    assert.equal(output.some(event => event.type === 'done'), false)
    assert.equal(output.at(-1).type, 'error')
  }
})

test('repeated terminal reason still requires DONE before a tool is executable', async t => {
  const f = await fixture(t, () => new Response([...toolPackets().slice(0, -1),
    { choices: [{ delta: {}, finish_reason: 'tool_calls' }] }].map(packet).join('')))
  const output = events(await (await f.send()).text())
  assert.equal(output.some(event => event.type === 'tool_call'), false)
  assert.equal(output.at(-1).type, 'error')
})

test('documented assistant terminal metadata is allowed with repeated tool and stop reasons', async t => {
  const terminal = reason => ({ choices: [{ delta: { role: 'assistant', content: '' }, finish_reason: reason }] })
  const f = await fixture(t, (_init, index) => new Response((index === 1
    ? [...toolPackets().slice(0, -1), terminal('tool_calls'), '[DONE]']
    : [{ choices: [{ delta: { content: '根据实际结果回答' }, finish_reason: 'stop' }] }, terminal('stop'), '[DONE]']
  ).map(packet).join('')))
  const first = events(await (await f.send()).text())
  assert.equal(first.filter(event => event.type === 'tool_call').length, 1)
  assert.equal(first.at(-1).type, 'done')
  const second = events(await (await f.send({ ...request, messages: replay })).text())
  assert.equal(second.at(-1).type, 'done')
  assert.equal(second.some(event => event.type === 'error'), false)
  assert.equal(f.calls.length, 2)
})

test('other terminal roles and valued delta fields remain rejected alongside assistant metadata', async t => {
  const deltas = [
    { role: 'user' }, { role: 'system' }, { role: 'tool' }, { role: '' },
    { role: 'assistant', content: 'new content' },
    { role: 'assistant', reasoning_content: 'new reasoning' },
    { role: 'assistant', tool_calls: [{ index: 0, function: { arguments: 'new arguments' } }] },
    ...['new value', false, 0, {}].map(value => ({ role: 'assistant', unknown_field: value })),
  ]
  for (const delta of deltas) {
    const terminal = { choices: [{ delta, finish_reason: 'tool_calls' }] }
    const f = await fixture(t, () => new Response([...toolPackets().slice(0, -1), terminal, '[DONE]'].map(packet).join('')))
    const output = events(await (await f.send()).text())
    assert.equal(output.some(event => event.type === 'tool_call'), false)
    assert.equal(output.some(event => event.type === 'done'), false)
    assert.equal(output.at(-1).type, 'error')
  }
})

test('legacy clients never receive or execute tool requests', async t => {
  const f = await fixture(t, () => new Response(toolPackets().map(packet).join('')))
  const output = events(await (await f.send({ messages: request.messages })).text())
  assert.equal(output[0].toolProtocol, undefined)
  assert.equal(output.some(event => event.type === 'tool_call'), false)
  assert.equal(output.at(-1).type, 'error')
})

test('congestion retry preserves tools and stays on the direct free model', async t => {
  const f = await fixture(t, (_, index) => index === 1
    ? Response.json({ error: { code: '1305' } }, { status: 429 }) : new Response(toolPackets().map(packet).join('')))
  const output = events(await (await f.send(request)).text())
  assert.equal(output[0].model, MODEL)
  assert.equal(output.at(-1).type, 'done')
  assert.equal(f.calls[0].model, MODEL)
  assert.deepEqual(f.calls[1].tools, f.calls[0].tools)
  assert.equal(f.calls[1].thinking, undefined)
})

test('a slow partial tool stream uses only one model request', async t => {
  const f = await fixture(t, () => new Response(new ReadableStream({ async start(controller) {
    controller.enqueue(new TextEncoder().encode(packet(toolPackets()[0])))
    await new Promise(resolve => setTimeout(resolve, 60))
    controller.enqueue(new TextEncoder().encode(toolPackets().slice(1).map(packet).join('')))
    controller.close()
  } })))
  const output = events(await (await f.send(request)).text())
  assert.equal(output[0].model, MODEL)
  assert.equal(output.at(-1).type, 'done')
  assert.equal(f.calls.length, 1)
})

test('disconnect during a partial tool call aborts upstream without executing or retrying', async t => {
  let stopped
  const stoppedPromise = new Promise(resolve => { stopped = resolve })
  const f = await fixture(t, init => new Response(new ReadableStream({ start(controller) {
    controller.enqueue(new TextEncoder().encode(packet(toolPackets()[0])))
    init.signal.addEventListener('abort', () => { controller.error(new Error('aborted')); stopped() }, { once: true })
  } })))
  const abort = new AbortController()
  const response = await f.send(request, abort.signal)
  const reader = response.body.getReader()
  const first = await reader.read()
  assert.doesNotMatch(new TextDecoder().decode(first.value), /event: tool_call/)
  abort.abort()
  await Promise.race([stoppedPromise, new Promise((_, reject) => setTimeout(() => reject(new Error('upstream was not aborted')), 1000))])
  assert.equal(f.calls.length, 1)
})

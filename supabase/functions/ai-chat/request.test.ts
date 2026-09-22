import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isChatRequest } from './request.ts'

test('malformed messages return false without throwing', () => {
  for (const entry of [null,undefined,1,'text',[],{}, {role:'user'}, {role:'system',content:'bad'}]) {
    assert.equal(isChatRequest({mode:'fast',messages:[entry]}),false)
  }
  for (const body of [null,[],{}, {mode:['fast'],messages:[{role:'user',content:'test'}]}, {mode:'invalid',messages:[]}, {mode:'fast',messages:[],context:[]}]) assert.equal(isChatRequest(body),false)
})
test('size and context boundaries are enforced', () => {
  const message = {role:'user',content:'a'.repeat(20_000)}
  assert.equal(isChatRequest({mode:'deep',messages:Array(6).fill(message)}),true)
  assert.equal(isChatRequest({mode:'deep',messages:Array(7).fill(message)}),false)
  assert.equal(isChatRequest({mode:'fast',messages:[{...message,content:'a'.repeat(20_001)}]}),false)
  assert.equal(isChatRequest({mode:'fast',messages:[message],context:[]}),false)
  assert.equal(isChatRequest({mode:'fast',messages:[message],context:{locale:'zh-CN'}}),true)
})

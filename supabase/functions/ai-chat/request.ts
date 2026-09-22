export type ChatRequest = {
  mode: 'fast' | 'deep'
  messages: { role:'user'|'assistant'; content:string }[]
  context?: { dateRange?:unknown; timeSummary?:unknown; goals?:unknown; locale?:string }
}

export function isChatRequest(value:unknown): value is ChatRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const body = value as Record<string,unknown>
  if (typeof body.mode !== 'string' || !['fast','deep'].includes(body.mode) || !Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 60) return false
  let length = 0
  for (const message of body.messages) {
    if (!message || typeof message !== 'object' || Array.isArray(message)) return false
    if (!['user','assistant'].includes(message.role) || typeof message.content !== 'string' || message.content.length > 20_000) return false
    length += message.content.length
    if (length > 120_000) return false
  }
  return body.context == null || (typeof body.context === 'object' && !Array.isArray(body.context))
}

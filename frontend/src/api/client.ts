import axios from 'axios'
import type {
  Approval, ChatMessage, ChatSession, EvalDataset, EvalResult, EvalRun,
  MCPServer, MCPTool, Overview, Skill, SubAgent, Trace, User,
} from '@/types'

const TOKEN_KEY = 'agent_platform_token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

export const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (r) => r,
  (error) => {
    if (error.response?.status === 401 && !location.pathname.startsWith('/login')) {
      tokenStore.clear()
      location.href = '/login'
    }
    const detail = error.response?.data?.detail
    error.message = typeof detail === 'string' ? detail : (detail ? JSON.stringify(detail) : error.message)
    return Promise.reject(error)
  },
)

// ---------------------------------------------------------------- 认证
export const authApi = {
  login: (username: string, password: string) =>
    http.post<{ access_token: string; user: User }>('/auth/login', { username, password }).then((r) => r.data),
  me: () => http.get<User>('/auth/me').then((r) => r.data),
}

// ---------------------------------------------------------------- MCP
export const mcpApi = {
  catalog: () => http.get<any[]>('/mcp/builtin-catalog').then((r) => r.data),
  servers: () => http.get<MCPServer[]>('/mcp/servers').then((r) => r.data),
  createServer: (payload: Partial<MCPServer>) => http.post<MCPServer>('/mcp/servers', payload).then((r) => r.data),
  updateServer: (id: string, payload: any) => http.patch<MCPServer>(`/mcp/servers/${id}`, payload).then((r) => r.data),
  deleteServer: (id: string) => http.delete(`/mcp/servers/${id}`),
  discover: (id: string) => http.post<MCPServer>(`/mcp/servers/${id}/discover`).then((r) => r.data),
  health: (id: string) => http.post(`/mcp/servers/${id}/health`).then((r) => r.data),
  resetCircuit: (id: string) => http.post(`/mcp/servers/${id}/reset-circuit`).then((r) => r.data),
  tools: (serverId?: string) =>
    http.get<MCPTool[]>('/mcp/tools', { params: serverId ? { server_id: serverId } : {} }).then((r) => r.data),
  updateTool: (id: string, payload: any) => http.patch<MCPTool>(`/mcp/tools/${id}`, payload).then((r) => r.data),
  invoke: (id: string, args: Record<string, any>) =>
    http.post(`/mcp/tools/${id}/invoke`, { arguments: args }).then((r) => r.data),
}

// ---------------------------------------------------------------- Skill
export const skillApi = {
  list: () => http.get<Skill[]>('/skills').then((r) => r.data),
  create: (payload: any) => http.post<Skill>('/skills', payload).then((r) => r.data),
  update: (id: string, payload: any) => http.patch<Skill>(`/skills/${id}`, payload).then((r) => r.data),
  remove: (id: string) => http.delete(`/skills/${id}`),
  dryRun: (id: string, inputs: Record<string, any>) =>
    http.post(`/skills/${id}/dry-run`, inputs).then((r) => r.data),
}

// ---------------------------------------------------------------- 子 Agent
export const agentApi = {
  list: () => http.get<SubAgent[]>('/agents').then((r) => r.data),
  create: (payload: any) => http.post<SubAgent>('/agents', payload).then((r) => r.data),
  update: (id: string, payload: any) => http.patch<SubAgent>(`/agents/${id}`, payload).then((r) => r.data),
  remove: (id: string) => http.delete(`/agents/${id}`),
  effective: (id: string) => http.get(`/agents/${id}/effective-capabilities`).then((r) => r.data),
}

// ---------------------------------------------------------------- 会话
export const chatApi = {
  sessions: () => http.get<ChatSession[]>('/chat/sessions').then((r) => r.data),
  createSession: (title: string) => http.post<ChatSession>('/chat/sessions', { title }).then((r) => r.data),
  deleteSession: (id: string) => http.delete(`/chat/sessions/${id}`),
  messages: (id: string) => http.get<ChatMessage[]>(`/chat/sessions/${id}/messages`).then((r) => r.data),
}

// ---------------------------------------------------------------- 审批
export const approvalApi = {
  list: (params?: { status?: string; session_id?: string }) =>
    http.get<Approval[]>('/approvals', { params }).then((r) => r.data),
  decide: (id: string, decision: 'approved' | 'rejected', comment: string) =>
    http.post<Approval>(`/approvals/${id}/decide`, { decision, comment }).then((r) => r.data),
}

// ---------------------------------------------------------------- 观测
export const traceApi = {
  list: (params?: Record<string, any>) => http.get<Trace[]>('/traces', { params }).then((r) => r.data),
  detail: (id: string) => http.get<Trace>(`/traces/${id}`).then((r) => r.data),
  overview: (days = 7) => http.get<Overview>('/metrics/overview', { params: { days } }).then((r) => r.data),
  toolMetrics: () => http.get<any[]>('/metrics/tools').then((r) => r.data),
  capabilityMetrics: () => http.get<any>('/metrics/capabilities').then((r) => r.data),
}

// ---------------------------------------------------------------- 评测
export const evalApi = {
  datasets: () => http.get<EvalDataset[]>('/evals/datasets').then((r) => r.data),
  createDataset: (payload: any) => http.post<EvalDataset>('/evals/datasets', payload).then((r) => r.data),
  addCase: (datasetId: string, payload: any) =>
    http.post(`/evals/datasets/${datasetId}/cases`, payload).then((r) => r.data),
  deleteCase: (caseId: string) => http.delete(`/evals/cases/${caseId}`),
  startRun: (datasetId: string) => http.post<EvalRun>(`/evals/datasets/${datasetId}/run`).then((r) => r.data),
  runs: (datasetId?: string) =>
    http.get<EvalRun[]>('/evals/runs', { params: datasetId ? { dataset_id: datasetId } : {} }).then((r) => r.data),
  run: (id: string) => http.get<EvalRun>(`/evals/runs/${id}`).then((r) => r.data),
  results: (runId: string, onlyFailed = false) =>
    http.get<EvalResult[]>(`/evals/runs/${runId}/results`, { params: { only_failed: onlyFailed } }).then((r) => r.data),
  label: (resultId: string, label: 'pass' | 'fail', comment: string) =>
    http.post(`/evals/results/${resultId}/label`, { human_label: label, human_comment: comment }).then((r) => r.data),
}

// ---------------------------------------------------------------- SSE
/**
 * 以 POST 发起 SSE。EventSource 只支持 GET 且无法带 Authorization 头，
 * 因此这里手动解析 fetch 的流式响应体。
 */
export async function streamChat(
  body: { session_id?: string | null; message?: string },
  onEvent: (event: string, data: any) => void,
  options: { signal?: AbortSignal; path?: string } = {},
): Promise<void> {
  const response = await fetch(options.path ?? '/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenStore.get()}` },
    body: JSON.stringify(body),
    signal: options.signal,
  })

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '')
    throw new Error(text || `请求失败：HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let boundary = buffer.indexOf('\n\n')
    while (boundary !== -1) {
      const chunk = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      let eventName = 'message'
      const dataLines: string[] = []
      for (const line of chunk.split('\n')) {
        if (line.startsWith('event: ')) eventName = line.slice(7).trim()
        else if (line.startsWith('data: ')) dataLines.push(line.slice(6))
      }
      if (dataLines.length) {
        try {
          onEvent(eventName, JSON.parse(dataLines.join('\n')))
        } catch {
          onEvent(eventName, { raw: dataLines.join('\n') })
        }
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

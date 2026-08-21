export interface User {
  id: string
  username: string
  display_name: string
  role: 'admin' | 'developer' | 'viewer'
  tenant_id: string
}

export interface MCPTool {
  id: string
  server_id: string
  name: string
  display_name: string
  description: string
  input_schema: Record<string, any>
  risk_level: 'low' | 'medium' | 'high'
  required_scopes: string[]
  read_only: boolean
  enabled: boolean
  call_count: number
  success_count: number
  total_latency_ms: number
}

export interface MCPServer {
  id: string
  name: string
  display_name: string
  description: string
  business_domain: string
  transport: 'builtin' | 'stdio' | 'streamable_http' | 'sse'
  endpoint: string
  command: string
  args: string[]
  auth_type: string
  owner: string
  tags: string[]
  enabled: boolean
  health_status: 'healthy' | 'degraded' | 'down' | 'unknown'
  last_health_at: string | null
  last_error: string
  circuit_state: 'closed' | 'open' | 'half_open'
  created_at: string
  tools: MCPTool[]
}

export interface SkillStep {
  id: string
  type: 'tool' | 'llm'
  tool?: string
  args?: Record<string, any>
  input_map?: Record<string, string>
  prompt?: string
  optional?: boolean
  description?: string
}

export interface Skill {
  id: string
  name: string
  display_name: string
  description: string
  business_domain: string
  steps: SkillStep[]
  input_schema: Record<string, any>
  output_template: string
  risk_level: string
  required_scopes: string[]
  examples: string[]
  negative_examples: string[]
  owner: string
  version: string
  status: string
  enabled: boolean
  tags: string[]
  call_count: number
  success_count: number
  created_at: string
  updated_at: string
}

export interface SubAgent {
  id: string
  name: string
  display_name: string
  description: string
  business_domain: string
  system_prompt: string
  tool_allowlist: string[]
  skill_names: string[]
  required_scopes: string[]
  positive_examples: string[]
  negative_examples: string[]
  usage_examples: string[]
  owner: string
  version: string
  risk_level: string
  status: string
  enabled: boolean
  tenant_id: string
  model: string
  max_iterations: number
  tags: string[]
  call_count: number
  success_count: number
  created_at: string
  updated_at: string
}

export interface ChatSession {
  id: string
  title: string
  status: string
  turn_count: number
  total_tokens: number
  summary: string
  created_at: string
  updated_at: string
}

export interface ChatMessage {
  id: string
  session_id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  meta: Record<string, any>
  created_at: string
}

export interface Approval {
  id: string
  session_id: string
  trace_id: string
  executor_type: string
  executor_name: string
  tool_name: string
  tool_args: Record<string, any>
  risk_level: string
  risk_reason: string
  impact_scope: string
  status: 'pending' | 'approved' | 'rejected' | 'expired'
  decided_by: string
  decision_comment: string
  decided_at: string | null
  created_at: string
}

export interface TraceSpan {
  id: string
  sequence: number
  name: string
  span_type: string
  input: Record<string, any>
  output: Record<string, any>
  status: string
  error: string
  latency_ms: number
  created_at: string
}

export interface Trace {
  id: string
  session_id: string
  turn_index: number
  user_id: string
  question: string
  answer: string
  route_type: string
  route_target: string
  route_confidence: number
  route_reason: string
  visible_tool_count: number
  total_tool_count: number
  prompt_tokens: number
  completion_tokens: number
  total_tokens: number
  latency_ms: number
  iterations: number
  tool_calls: number
  tool_failures: number
  context_compressed: boolean
  model_used: string
  model_fallback: boolean
  degraded_to_rules: boolean
  status: string
  error_type: string
  created_at: string
  spans?: TraceSpan[]
}

export interface Overview {
  window_days: number
  total_turns: number
  success_rate: number
  tool_calls: number
  tool_success_rate: number
  total_tokens: number
  avg_latency_ms: number
  avg_visible_tools: number
  compression_rate: number
  route_distribution: { route_type: string; count: number }[]
  daily: { date: string; turns: number; tokens: number; avg_latency_ms: number }[]
  errors: { error_type: string; count: number }[]
  assets: { servers: number; tools: number; skills: number; agents: number }
  circuits: Record<string, { state: string; samples: number; failure_rate: number }>
}

export interface EvalCase {
  id: string
  dataset_id: string
  question: string
  expected_route_type: string
  expected_target: string
  expected_answer: string
  tags: string[]
  created_at: string
}

export interface EvalDataset {
  id: string
  name: string
  description: string
  owner: string
  created_at: string
  cases: EvalCase[]
}

export interface EvalRun {
  id: string
  dataset_id: string
  name: string
  status: string
  total: number
  finished: number
  metrics: Record<string, any>
  triggered_by: string
  created_at: string
  finished_at: string | null
}

export interface EvalResult {
  id: string
  run_id: string
  case_id: string
  question: string
  answer: string
  expected_route_type: string
  expected_target: string
  actual_route_type: string
  actual_target: string
  route_correct: boolean
  tool_success: boolean
  latency_ms: number
  total_tokens: number
  llm_score: number | null
  llm_comment: string
  human_label: string
  human_comment: string
  trace_id: string
  error: string
}

/** SSE 事件 */
export type StreamEvent =
  | { event: 'session'; data: { session_id: string; title: string } }
  | { event: 'status'; data: { phase: string; message: string } }
  | { event: 'context'; data: Record<string, any> }
  | { event: 'route'; data: Record<string, any> }
  | { event: 'tool_start'; data: Record<string, any> }
  | { event: 'tool_end'; data: Record<string, any> }
  | { event: 'skill_start'; data: Record<string, any> }
  | { event: 'skill_step'; data: Record<string, any> }
  | { event: 'skill_end'; data: Record<string, any> }
  | { event: 'agent_start'; data: Record<string, any> }
  | { event: 'agent_end'; data: Record<string, any> }
  | { event: 'guard'; data: Record<string, any> }
  | { event: 'approval_required'; data: Record<string, any> }
  | { event: 'delta'; data: { text: string } }
  | { event: 'answer'; data: { text: string; corrected?: boolean } }
  | { event: 'error'; data: { message: string } }
  | { event: 'done'; data: Record<string, any> }

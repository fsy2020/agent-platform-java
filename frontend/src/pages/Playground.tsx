import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert, App, Button, Card, Descriptions, Empty, Input, List, Modal, Popconfirm,
  Space, Spin, Tag, Tooltip, Typography,
} from 'antd'
import {
  CheckCircleOutlined, ClearOutlined, CloseCircleOutlined, DeleteOutlined,
  PlusOutlined, SendOutlined, WarningOutlined,
} from '@ant-design/icons'

import { approvalApi, chatApi, streamChat } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { ChatSession } from '@/types'

interface Bubble {
  role: 'user' | 'assistant'
  content: string
  meta?: Record<string, any>
}

interface TraceItem {
  kind: string
  title: string
  meta?: string
  detail?: string
  ok?: boolean
}

const SAMPLES = [
  'E1001 的年假还剩多少？',
  'VPN 连不上怎么办？',
  '东京精密制造这个客户的订单总额是多少？',
  '差旅住宿一线城市能报多少？',
  '帮我把 E1002 的域账号密码重置一下',
]

export default function Playground() {
  const { message } = App.useApp()
  const { can } = useAuth()
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [bubbles, setBubbles] = useState<Bubble[]>([])
  const [traces, setTraces] = useState<TraceItem[]>([])
  const [input, setInput] = useState('')
  const [running, setRunning] = useState(false)
  const [pendingApproval, setPendingApproval] = useState<any>(null)
  const [summary, setSummary] = useState<any>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  const loadSessions = useCallback(() => {
    chatApi.sessions().then(setSessions).catch(() => undefined)
  }, [])

  useEffect(() => { loadSessions() }, [loadSessions])

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight, behavior: 'smooth' })
  }, [bubbles, traces])

  const openSession = async (id: string) => {
    setSessionId(id)
    setTraces([])
    setSummary(null)
    setPendingApproval(null)
    const rows = await chatApi.messages(id)
    setBubbles(rows.map((m) => ({ role: m.role as any, content: m.content, meta: m.meta })))
  }

  const newSession = () => {
    setSessionId(null)
    setBubbles([])
    setTraces([])
    setSummary(null)
    setPendingApproval(null)
  }

  const pushTrace = (item: TraceItem) => setTraces((prev) => [...prev, item])

  const handleEvent = (event: string, data: any) => {
    switch (event) {
      case 'session':
        setSessionId(data.session_id)
        loadSessions()
        break
      case 'context':
        pushTrace({
          kind: 'context',
          title: data.compressed ? '上下文装配（已触发压缩）' : '上下文装配',
          meta: data.compressed && data.before_tokens
            ? `压缩中段 ${data.dropped_turns} 轮：${data.before_tokens} → ${data.after_tokens} tokens`
            : `${data.context_tokens ?? 0} tokens · 可见能力 ${(data.visible_agents ?? 0)} Agent / ${(data.visible_skills ?? 0)} Skill / ${(data.visible_tools ?? 0)} 工具（平台共 ${data.total_tools ?? 0} 工具）`,
        })
        break
      case 'route':
        pushTrace({
          kind: 'route',
          title: `路由决策 → ${routeLabel(data.route_type)}${data.target ? ` · ${data.target}` : ''}`,
          meta: `置信度 ${data.confidence} · ${data.model}${data.degraded ? ' · 规则引擎降级' : ''}`,
          detail: data.reason,
        })
        break
      case 'agent_start':
        pushTrace({
          kind: 'agent',
          title: `分发子 Agent · ${data.display_name || data.agent}`,
          meta: `工具边界 ${data.tools?.length ?? 0} 个 · 预算 ${data.budget} 步`,
          detail: (data.tools || []).join('\n'),
        })
        break
      case 'skill_start':
        pushTrace({
          kind: 'agent',
          title: `执行 Skill · ${data.display_name || data.skill}`,
          meta: `${data.steps} 个步骤`,
          detail: JSON.stringify(data.inputs, null, 2),
        })
        break
      case 'tool_start':
        pushTrace({
          kind: 'tool-start',
          title: `调用 ${data.tool}`,
          meta: [data.skill && `Skill ${data.skill}/${data.step}`, data.agent && `子Agent ${data.agent}`,
                 data.risk_level === 'high' && '高风险'].filter(Boolean).join(' · '),
          detail: JSON.stringify(data.args, null, 2),
        })
        break
      case 'tool_end':
        pushTrace({
          kind: data.success ? 'tool' : 'tool-fail',
          title: `${data.success ? '✓' : '✗'} ${data.tool}`,
          meta: `${data.latency_ms}ms${data.attempts > 1 ? ` · 重试 ${data.attempts} 次` : ''}${data.error_type ? ` · ${data.error_type}` : ''}`,
          detail: data.summary,
          ok: data.success,
        })
        break
      case 'guard':
        pushTrace({ kind: 'guard', title: `护栏拦截 · ${data.kind}`, detail: data.message })
        break
      case 'approval_required':
        setPendingApproval(data)
        pushTrace({
          kind: 'approval',
          title: '⏸ 触发人工审批',
          meta: data.tool_name,
          detail: `${data.risk_reason}\n${data.impact_scope}`,
        })
        break
      case 'delta':
        setBubbles((prev) => {
          const next = [...prev]
          const last = next[next.length - 1]
          if (last?.role === 'assistant' && last.meta?.streaming) {
            next[next.length - 1] = { ...last, content: last.content + data.text }
          } else {
            next.push({ role: 'assistant', content: data.text, meta: { streaming: true } })
          }
          return next
        })
        break
      case 'answer':
        setBubbles((prev) => {
          const next = [...prev]
          const last = next[next.length - 1]
          if (last?.role === 'assistant' && last.meta?.streaming) {
            next[next.length - 1] = { role: 'assistant', content: data.text, meta: { corrected: data.corrected } }
          } else {
            next.push({ role: 'assistant', content: data.text, meta: { corrected: data.corrected } })
          }
          return next
        })
        break
      case 'error':
        message.error(data.message)
        break
      case 'done':
        setSummary(data)
        setBubbles((prev) =>
          prev.map((b, i) => (i === prev.length - 1 && b.role === 'assistant'
            ? { ...b, meta: { ...b.meta, streaming: false } } : b)),
        )
        break
    }
  }

  const send = async (text?: string) => {
    const content = (text ?? input).trim()
    if (!content || running) return

    setBubbles((prev) => [...prev, { role: 'user', content }])
    setInput('')
    setTraces([])
    setSummary(null)
    setPendingApproval(null)
    setRunning(true)

    abortRef.current = new AbortController()
    try {
      await streamChat({ session_id: sessionId, message: content }, handleEvent,
                       { signal: abortRef.current.signal })
    } catch (e: any) {
      if (e.name !== 'AbortError') message.error(e.message || '请求失败')
    } finally {
      setRunning(false)
      loadSessions()
    }
  }

  const decide = async (decision: 'approved' | 'rejected') => {
    if (!pendingApproval) return
    setRunning(true)
    try {
      await approvalApi.decide(pendingApproval.id, decision, decision === 'approved' ? '调试台放行' : '调试台拒绝')
      const approvalId = pendingApproval.id
      setPendingApproval(null)
      pushTrace({
        kind: 'approval',
        title: decision === 'approved' ? '✓ 审批放行，恢复执行' : '✗ 审批拒绝',
      })
      abortRef.current = new AbortController()
      await streamChat({}, handleEvent, {
        signal: abortRef.current.signal,
        path: `/api/chat/resume/${approvalId}`,
      })
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setRunning(false)
    }
  }

  return (
    <>
      <div className="page-header">
        <h2>对话调试台</h2>
        <p>右侧实时展示 Supervisor 的意图识别、执行路径选择、工具调用与护栏拦截全过程</p>
      </div>

      <div className="chat-shell">
        {/* 会话列表 */}
        <Card
          size="small"
          title="会话"
          styles={{ body: { padding: 0, overflowY: 'auto', height: 'calc(100% - 40px)' } }}
          extra={<Button size="small" type="text" icon={<PlusOutlined />} onClick={newSession} />}
        >
          <List
            size="small"
            dataSource={sessions}
            locale={{ emptyText: '暂无会话' }}
            renderItem={(s) => (
              <List.Item
                style={{
                  cursor: 'pointer', padding: '8px 12px',
                  background: s.id === sessionId ? '#e6f4ff' : undefined,
                }}
                onClick={() => openSession(s.id)}
                actions={[
                  <Popconfirm
                    key="d"
                    title="删除该会话？"
                    onConfirm={async (e) => {
                      e?.stopPropagation()
                      await chatApi.deleteSession(s.id)
                      if (s.id === sessionId) newSession()
                      loadSessions()
                    }}
                  >
                    <DeleteOutlined onClick={(e) => e.stopPropagation()} style={{ color: '#bbb' }} />
                  </Popconfirm>,
                ]}
              >
                <div style={{ overflow: 'hidden' }}>
                  <div style={{ fontSize: 13, whiteSpace: 'nowrap', textOverflow: 'ellipsis', overflow: 'hidden' }}>
                    {s.title}
                  </div>
                  <div style={{ fontSize: 11, color: '#999' }}>
                    {s.turn_count} 轮
                    {s.status === 'waiting_approval' && <Tag color="purple" style={{ marginLeft: 4 }}>待审批</Tag>}
                  </div>
                </div>
              </List.Item>
            )}
          />
        </Card>

        {/* 对话区 */}
        <Card
          size="small"
          styles={{ body: { padding: 0, display: 'flex', flexDirection: 'column', height: '100%' } }}
        >
          <div className="chat-body" ref={bodyRef}>
            {bubbles.length === 0 && (
              <div style={{ padding: '40px 20px', textAlign: 'center' }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="试试下面的示例问题" />
                <Space direction="vertical" style={{ marginTop: 12 }}>
                  {SAMPLES.map((s) => (
                    <Button key={s} size="small" onClick={() => send(s)}>{s}</Button>
                  ))}
                </Space>
              </div>
            )}

            {bubbles.map((b, i) => (
              <div key={i} className={`msg-row ${b.role}`}>
                <div className="msg-bubble">
                  {b.meta?.corrected && (
                    <Tag color="orange" style={{ marginBottom: 6 }}>
                      <WarningOutlined /> 防幻觉护栏已纠正
                    </Tag>
                  )}
                  {b.content}
                  {b.meta?.streaming && <Spin size="small" style={{ marginLeft: 6 }} />}
                </div>
              </div>
            ))}

            {pendingApproval && (
              <Alert
                type="warning"
                showIcon
                icon={<WarningOutlined />}
                style={{ marginTop: 8 }}
                message="高风险操作待人工审批"
                description={
                  <>
                    <Descriptions size="small" column={1} style={{ marginBottom: 10 }}>
                      <Descriptions.Item label="工具">
                        <span className="mono">{pendingApproval.tool_name}</span>
                      </Descriptions.Item>
                      <Descriptions.Item label="参数">
                        <span className="mono">{JSON.stringify(pendingApproval.tool_args)}</span>
                      </Descriptions.Item>
                      <Descriptions.Item label="风险原因">{pendingApproval.risk_reason}</Descriptions.Item>
                      <Descriptions.Item label="影响范围">{pendingApproval.impact_scope}</Descriptions.Item>
                    </Descriptions>
                    {can('decide') ? (
                      <Space>
                        <Button type="primary" size="small" icon={<CheckCircleOutlined />}
                                loading={running} onClick={() => decide('approved')}>
                          批准并继续执行
                        </Button>
                        <Button danger size="small" icon={<CloseCircleOutlined />}
                                loading={running} onClick={() => decide('rejected')}>
                          拒绝
                        </Button>
                      </Space>
                    ) : (
                      <Typography.Text type="secondary">当前角色无审批权限，请联系管理员处理</Typography.Text>
                    )}
                  </>
                }
              />
            )}

            {running && !pendingApproval && (
              <div style={{ textAlign: 'center', padding: 12 }}>
                <Spin size="small" /> <span style={{ color: '#999', fontSize: 12 }}>执行中…</span>
              </div>
            )}
          </div>

          <div style={{ borderTop: '1px solid var(--border-soft)', padding: 12 }}>
            <Space.Compact style={{ width: '100%' }}>
              <Input.TextArea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="输入你的请求，例如：帮我查一下 E1001 的年假余额"
                autoSize={{ minRows: 1, maxRows: 4 }}
                onPressEnter={(e) => {
                  if (!e.shiftKey) {
                    e.preventDefault()
                    send()
                  }
                }}
                disabled={running}
              />
              <Button type="primary" icon={<SendOutlined />} onClick={() => send()}
                      loading={running} disabled={!input.trim()}>
                发送
              </Button>
            </Space.Compact>
          </div>
        </Card>

        {/* 执行轨迹 */}
        <Card
          size="small"
          title="执行轨迹"
          extra={
            traces.length > 0 && (
              <Button size="small" type="text" icon={<ClearOutlined />} onClick={() => setTraces([])} />
            )
          }
          styles={{ body: { padding: 8, overflowY: 'auto', height: 'calc(100% - 40px)' } }}
        >
          {traces.length === 0 && (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="发送消息后展示编排过程" style={{ marginTop: 40 }} />
          )}

          <div className="trace-panel">
            {traces.map((t, i) => (
              <div key={i} className={`trace-item ${traceClass(t.kind)}`}>
                <div className="t-title">{t.title}</div>
                {t.meta && <div className="t-meta">{t.meta}</div>}
                {t.detail && <pre>{t.detail}</pre>}
              </div>
            ))}
          </div>

          {summary && (
            <Card size="small" style={{ marginTop: 10 }} title="本轮汇总">
              <Descriptions size="small" column={1}>
                <Descriptions.Item label="状态">
                  <Tag color={summary.status === 'success' ? 'green' : summary.status === 'blocked' ? 'red' : 'orange'}>
                    {summary.status}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label="迭代/工具">
                  {summary.iterations} 轮 · {summary.tool_calls} 次调用
                  {summary.tool_failures > 0 && <Tag color="red" style={{ marginLeft: 4 }}>{summary.tool_failures} 失败</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="耗时">{summary.latency_ms} ms</Descriptions.Item>
                <Descriptions.Item label="可见工具">
                  <Tooltip title="分层路由后单次请求实际暴露给模型的能力数">
                    {summary.visible_tool_count} / {summary.total_tool_count}
                  </Tooltip>
                </Descriptions.Item>
                <Descriptions.Item label="模型">
                  {summary.model_used}
                  {summary.model_fallback && <Tag color="orange" style={{ marginLeft: 4 }}>已切备用</Tag>}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          )}
        </Card>
      </div>
    </>
  )
}

function routeLabel(type: string): string {
  return ({
    tool: '直接调用工具', skill: '执行 Skill', agent: '分发子 Agent',
    answer: '直接回答', clarify: '澄清追问',
  } as Record<string, string>)[type] ?? type
}

function traceClass(kind: string): string {
  if (kind === 'route') return 'route'
  if (kind === 'tool' || kind === 'tool-start') return 'tool'
  if (kind === 'tool-fail') return 'tool-fail'
  if (kind === 'guard') return 'guard'
  if (kind === 'approval') return 'approval'
  return ''
}

import { useEffect, useState } from 'react'
import {
  App, Badge, Button, Card, Descriptions, Drawer, Form, Input, Modal, Popconfirm,
  Select, Space, Table, Tabs, Tag, Tooltip,
} from 'antd'
import {
  ApiOutlined, DeleteOutlined, HeartOutlined, PlusOutlined, ReloadOutlined, ThunderboltOutlined,
} from '@ant-design/icons'

import { mcpApi } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { MCPServer, MCPTool } from '@/types'

const HEALTH_BADGE: Record<string, { status: any; text: string }> = {
  healthy: { status: 'success', text: '健康' },
  degraded: { status: 'warning', text: '降级' },
  down: { status: 'error', text: '不可用' },
  unknown: { status: 'default', text: '未探测' },
}

const RISK_COLOR: Record<string, string> = { low: 'green', medium: 'orange', high: 'red' }

const TRANSPORT_HINT: Record<string, string> = {
  builtin: '平台内置演示 Server（无需外部依赖）',
  stdio: '通过标准输入输出启动本地 MCP Server 进程',
  streamable_http: '通过 Streamable HTTP 连接远程 MCP Server',
  sse: '通过 SSE 连接远程 MCP Server',
}

export default function McpRegistry() {
  const { message } = App.useApp()
  const { can } = useAuth()
  const [servers, setServers] = useState<MCPServer[]>([])
  const [catalog, setCatalog] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [modalOpen, setModalOpen] = useState(false)
  const [invokeTool, setInvokeTool] = useState<MCPTool | null>(null)
  const [form] = Form.useForm()
  const transport = Form.useWatch('transport', form)

  const load = () => {
    setLoading(true)
    mcpApi.servers().then(setServers).finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    mcpApi.catalog().then(setCatalog).catch(() => undefined)
  }, [])

  const register = async () => {
    const values = await form.validateFields()
    try {
      await mcpApi.createServer(values)
      message.success('注册成功，已自动发现工具')
      setModalOpen(false)
      form.resetFields()
      load()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const act = async (fn: Promise<any>, ok: string) => {
    try {
      await fn
      message.success(ok)
      load()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>MCP 注册中心</h2>
          <p>把内部 REST API 标准化封装为 MCP 工具，统一治理风险等级、权限 scope 与健康状态</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          {can('write') && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
              注册 Server
            </Button>
          )}
        </Space>
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={servers}
          pagination={false}
          expandable={{
            expandedRowRender: (server) => (
              <Table
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={server.tools}
                columns={[
                  {
                    title: '工具',
                    dataIndex: 'name',
                    render: (v: string) => <span className="mono">{server.name}.{v}</span>,
                  },
                  { title: '描述', dataIndex: 'description', ellipsis: true },
                  {
                    title: '风险',
                    dataIndex: 'risk_level',
                    width: 90,
                    render: (v: string, row: MCPTool) => (
                      <Space size={4}>
                        <Tag color={RISK_COLOR[v]}>{v}</Tag>
                        {!row.read_only && <Tag color="volcano">写</Tag>}
                      </Space>
                    ),
                  },
                  {
                    title: '权限 scope',
                    dataIndex: 'required_scopes',
                    width: 130,
                    render: (v: string[]) => v?.length ? v.map((s) => <Tag key={s}>{s}</Tag>) : <span style={{ color: '#bbb' }}>无</span>,
                  },
                  {
                    title: '调用/成功',
                    width: 100,
                    render: (_: any, row: MCPTool) => `${row.call_count} / ${row.success_count}`,
                  },
                  {
                    title: '均耗时',
                    width: 90,
                    render: (_: any, row: MCPTool) =>
                      row.call_count ? `${Math.round(row.total_latency_ms / row.call_count)}ms` : '-',
                  },
                  {
                    title: '操作',
                    width: 90,
                    render: (_: any, row: MCPTool) =>
                      can('write') && (
                        <Button size="small" icon={<ThunderboltOutlined />} onClick={() => setInvokeTool(row)}>
                          试调
                        </Button>
                      ),
                  },
                ]}
              />
            ),
          }}
          columns={[
            {
              title: 'Server',
              dataIndex: 'name',
              render: (v: string, row: MCPServer) => (
                <div>
                  <div style={{ fontWeight: 600 }}>{row.display_name || v}</div>
                  <div className="mono" style={{ color: '#8c8c8c' }}>{v}</div>
                </div>
              ),
            },
            { title: '业务域', dataIndex: 'business_domain', width: 100, render: (v) => <Tag>{v}</Tag> },
            {
              title: '传输',
              dataIndex: 'transport',
              width: 140,
              render: (v: string, row: MCPServer) => (
                <Tooltip title={row.endpoint || row.command || TRANSPORT_HINT[v]}>
                  <Tag color="blue">{v}</Tag>
                </Tooltip>
              ),
            },
            { title: '工具数', width: 80, render: (_: any, row: MCPServer) => row.tools.length },
            {
              title: '健康',
              dataIndex: 'health_status',
              width: 110,
              render: (v: string, row: MCPServer) => (
                <Tooltip title={row.last_error || '正常'}>
                  <Badge {...(HEALTH_BADGE[v] ?? HEALTH_BADGE.unknown)} />
                </Tooltip>
              ),
            },
            {
              title: '熔断',
              dataIndex: 'circuit_state',
              width: 100,
              render: (v: string) => (
                <Tag color={v === 'closed' ? 'green' : v === 'half_open' ? 'orange' : 'red'}>
                  {v === 'closed' ? '正常' : v === 'half_open' ? '半开探活' : '已熔断'}
                </Tag>
              ),
            },
            { title: 'Owner', dataIndex: 'owner', width: 100 },
            {
              title: '操作',
              width: 250,
              render: (_: any, row: MCPServer) => (
                <Space size={4}>
                  <Button size="small" icon={<HeartOutlined />}
                          onClick={() => act(mcpApi.health(row.id), '健康探活完成')}>
                    探活
                  </Button>
                  {can('write') && (
                    <>
                      <Button size="small" icon={<ReloadOutlined />}
                              onClick={() => act(mcpApi.discover(row.id), '工具已热加载')}>
                        重新发现
                      </Button>
                      {row.circuit_state !== 'closed' && (
                        <Button size="small" danger onClick={() => act(mcpApi.resetCircuit(row.id), '熔断已恢复')}>
                          恢复
                        </Button>
                      )}
                      <Popconfirm title="注销该 Server？其工具将一并移除" onConfirm={() => act(mcpApi.deleteServer(row.id), '已注销')}>
                        <Button size="small" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    </>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="注册 MCP Server"
        open={modalOpen}
        onOk={register}
        onCancel={() => setModalOpen(false)}
        width={640}
        okText="注册并发现工具"
      >
        <Form form={form} layout="vertical" initialValues={{ transport: 'builtin', auth_type: 'none' }}>
          <Form.Item name="transport" label="传输方式" rules={[{ required: true }]}>
            <Select
              options={Object.keys(TRANSPORT_HINT).map((k) => ({
                value: k,
                label: `${k} —— ${TRANSPORT_HINT[k]}`,
              }))}
            />
          </Form.Item>

          {transport === 'builtin' ? (
            <Form.Item name="name" label="内置 Server" rules={[{ required: true, message: '请选择' }]}
                       extra="内置 Server 用于演示与联调，无需任何外部依赖">
              <Select
                options={catalog.map((c) => ({
                  value: c.name,
                  label: `${c.display_name}（${c.tool_count} 个工具）`,
                  disabled: servers.some((s) => s.name === c.name),
                }))}
                onChange={(v) => {
                  const item = catalog.find((c) => c.name === v)
                  if (item) {
                    form.setFieldsValue({
                      display_name: item.display_name,
                      description: item.description,
                      business_domain: item.business_domain,
                    })
                  }
                }}
              />
            </Form.Item>
          ) : (
            <Form.Item name="name" label="Server 名称（工具全名前缀）" rules={[{ required: true }]}>
              <Input placeholder="如 jira、gitlab" />
            </Form.Item>
          )}

          <Form.Item name="display_name" label="显示名称">
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="business_domain" label="业务域">
            <Input placeholder="hr / it / sales / finance" />
          </Form.Item>

          {transport === 'stdio' && (
            <>
              <Form.Item name="command" label="启动命令" rules={[{ required: true }]}>
                <Input placeholder="如 npx" />
              </Form.Item>
              <Form.Item name="args" label="参数（逗号分隔）" getValueFromEvent={(e) =>
                e.target.value.split(',').map((s: string) => s.trim()).filter(Boolean)}>
                <Input placeholder="-y, @modelcontextprotocol/server-filesystem, /data" />
              </Form.Item>
            </>
          )}

          {(transport === 'streamable_http' || transport === 'sse') && (
            <>
              <Form.Item name="endpoint" label="服务地址" rules={[{ required: true }]}>
                <Input placeholder="https://mcp.example.com/mcp" />
              </Form.Item>
              <Form.Item name="auth_type" label="鉴权方式">
                <Select
                  options={[
                    { value: 'none', label: '无' },
                    { value: 'bearer', label: 'Bearer Token' },
                    { value: 'api_key', label: 'API Key' },
                    { value: 'oauth2', label: 'OAuth2' },
                  ]}
                />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>

      <ToolInvokeDrawer tool={invokeTool} onClose={() => setInvokeTool(null)} />
    </>
  )
}

function ToolInvokeDrawer({ tool, onClose }: { tool: MCPTool | null; onClose: () => void }) {
  const { message } = App.useApp()
  const [args, setArgs] = useState('{}')
  const [result, setResult] = useState<any>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (tool) {
      const props = (tool.input_schema?.properties ?? {}) as Record<string, any>
      const template: Record<string, any> = {}
      for (const key of Object.keys(props)) template[key] = ''
      setArgs(JSON.stringify(template, null, 2))
      setResult(null)
    }
  }, [tool])

  const run = async () => {
    if (!tool) return
    let parsed: Record<string, any>
    try {
      parsed = JSON.parse(args)
    } catch {
      message.error('参数不是合法 JSON')
      return
    }
    // 去掉未填写的空值，避免把空串当作有效入参
    const payload = Object.fromEntries(Object.entries(parsed).filter(([, v]) => v !== '' && v !== null))
    setLoading(true)
    try {
      setResult(await mcpApi.invoke(tool.id, payload))
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Drawer title={`试调工具：${tool?.name}`} open={!!tool} onClose={onClose} width={620}>
      {tool && (
        <>
          <Descriptions size="small" column={1} bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="描述">{tool.description}</Descriptions.Item>
            <Descriptions.Item label="风险等级">
              <Tag color={RISK_COLOR[tool.risk_level]}>{tool.risk_level}</Tag>
              {!tool.read_only && <Tag color="volcano">写操作 · 运行时触发人工审批</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="必填参数">
              {(tool.input_schema?.required ?? []).join(', ') || '无'}
            </Descriptions.Item>
          </Descriptions>

          <Tabs
            items={[
              {
                key: 'invoke',
                label: '调用',
                children: (
                  <>
                    <Input.TextArea rows={8} value={args} onChange={(e) => setArgs(e.target.value)} className="mono" />
                    <Button type="primary" icon={<ApiOutlined />} loading={loading} onClick={run} style={{ marginTop: 12 }}>
                      发起调用
                    </Button>
                    {result && (
                      <div style={{ marginTop: 16 }}>
                        <Tag color={result.success ? 'green' : 'red'}>
                          {result.success ? '成功' : `失败：${result.error_type}`}
                        </Tag>
                        <Tag>{result.latency_ms}ms</Tag>
                        <Tag>重试 {result.attempts} 次</Tag>
                        <div className="code-block" style={{ marginTop: 8 }}>
                          {JSON.stringify(result.data ?? result.error_message, null, 2)}
                        </div>
                      </div>
                    )}
                  </>
                ),
              },
              {
                key: 'schema',
                label: '参数 Schema',
                children: <div className="code-block">{JSON.stringify(tool.input_schema, null, 2)}</div>,
              },
            ]}
          />
        </>
      )}
    </Drawer>
  )
}

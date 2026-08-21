import { useEffect, useState } from 'react'
import {
  Alert, App, Button, Card, Col, Descriptions, Drawer, Form, Input, InputNumber, Modal,
  Popconfirm, Row, Select, Space, Statistic, Table, Tag,
} from 'antd'
import { DeleteOutlined, EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons'

import { agentApi, mcpApi, skillApi } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { SubAgent } from '@/types'

const RISK_COLOR: Record<string, string> = { low: 'green', medium: 'orange', high: 'red' }

export default function Agents() {
  const { message } = App.useApp()
  const { can } = useAuth()
  const [agents, setAgents] = useState<SubAgent[]>([])
  const [toolOptions, setToolOptions] = useState<{ value: string; label: string }[]>([])
  const [skillOptions, setSkillOptions] = useState<{ value: string; label: string }[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<SubAgent | null>(null)
  const [creating, setCreating] = useState(false)
  const [inspect, setInspect] = useState<SubAgent | null>(null)

  const load = () => {
    setLoading(true)
    agentApi.list().then(setAgents).finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    mcpApi.servers().then((servers) => {
      const opts: { value: string; label: string }[] = []
      for (const s of servers) {
        for (const t of s.tools) {
          opts.push({
            value: `${s.name}.${t.name}`,
            label: `${s.name}.${t.name}${t.read_only ? '' : ' [写]'} — ${t.description.slice(0, 26)}`,
          })
        }
      }
      setToolOptions(opts)
    })
    skillApi.list().then((rows) =>
      setSkillOptions(rows.map((s) => ({ value: s.name, label: `${s.name} — ${s.display_name}` }))),
    )
  }, [])

  const remove = async (id: string) => {
    try {
      await agentApi.remove(id)
      message.success('已下线')
      load()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>子 Agent 注册中心</h2>
          <p>业务方动态创建子 Agent，绑定 MCP 工具 allowlist 与 Skill；运行时按身份、权限与意图裁剪暴露</p>
        </div>
        {can('write') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            注册子 Agent
          </Button>
        )}
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="路由质量要求"
        description="description 必须说明能力范围与输入输出规范，且至少提供一条正样例 —— 这是主 Agent 正确路由的依据，也是低置信度回流优化的基线。"
      />

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={agents}
          pagination={false}
          expandable={{
            expandedRowRender: (agent) => (
              <div style={{ padding: '8px 16px', fontSize: 12.5 }}>
                <div style={{ marginBottom: 8 }}>
                  <b>System Prompt：</b>
                  <div className="code-block" style={{ marginTop: 4 }}>{agent.system_prompt || '（未设置）'}</div>
                </div>
                <div><b>工具 allowlist：</b>{(agent.tool_allowlist || []).map((t) => <Tag key={t} className="mono">{t}</Tag>)}</div>
                <div style={{ marginTop: 4 }}>
                  <b>绑定 Skill：</b>
                  {(agent.skill_names || []).length
                    ? agent.skill_names.map((s) => <Tag key={s} color="purple">{s}</Tag>)
                    : <span style={{ color: '#bbb' }}>无</span>}
                </div>
                <div style={{ marginTop: 4 }}>
                  <b>正样例：</b>{(agent.positive_examples || []).map((e) => <Tag key={e} color="green">{e}</Tag>)}
                </div>
                <div style={{ marginTop: 4 }}>
                  <b>负样例：</b>
                  {(agent.negative_examples || []).length
                    ? agent.negative_examples.map((e) => <Tag key={e} color="red">{e}</Tag>)
                    : <span style={{ color: '#bbb' }}>无</span>}
                </div>
              </div>
            ),
          }}
          columns={[
            {
              title: '子 Agent',
              dataIndex: 'name',
              render: (v: string, row: SubAgent) => (
                <div>
                  <div style={{ fontWeight: 600 }}>{row.display_name || v}</div>
                  <div className="mono" style={{ color: '#8c8c8c' }}>{v}</div>
                </div>
              ),
            },
            { title: '能力描述', dataIndex: 'description', ellipsis: true },
            { title: '业务域', dataIndex: 'business_domain', width: 90, render: (v) => <Tag>{v}</Tag> },
            {
              title: '工具/Skill',
              width: 100,
              render: (_: any, row: SubAgent) =>
                `${row.tool_allowlist?.length ?? 0} / ${row.skill_names?.length ?? 0}`,
            },
            {
              title: '风险',
              dataIndex: 'risk_level',
              width: 80,
              render: (v: string) => <Tag color={RISK_COLOR[v]}>{v}</Tag>,
            },
            { title: '预算', dataIndex: 'max_iterations', width: 70, render: (v) => `${v} 步` },
            { title: '版本', dataIndex: 'version', width: 80 },
            {
              title: '状态',
              width: 90,
              render: (_: any, row: SubAgent) => (
                <Tag color={row.enabled && row.status === 'published' ? 'green' : 'default'}>
                  {!row.enabled ? '已停用' : row.status === 'published' ? '已发布' : row.status}
                </Tag>
              ),
            },
            { title: 'Owner', dataIndex: 'owner', width: 90 },
            {
              title: '操作',
              width: 170,
              render: (_: any, row: SubAgent) => (
                <Space size={4}>
                  <Button size="small" icon={<EyeOutlined />} onClick={() => setInspect(row)}>生效能力</Button>
                  {can('write') && (
                    <>
                      <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(row)} />
                      <Popconfirm title="确认下线该子 Agent？" onConfirm={() => remove(row.id)}>
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

      <AgentForm
        open={creating || !!editing}
        agent={editing}
        toolOptions={toolOptions}
        skillOptions={skillOptions}
        onClose={() => { setCreating(false); setEditing(null) }}
        onSaved={() => { setCreating(false); setEditing(null); load() }}
      />

      <EffectiveDrawer agent={inspect} onClose={() => setInspect(null)} />
    </>
  )
}

function AgentForm({ open, agent, toolOptions, skillOptions, onClose, onSaved }: {
  open: boolean
  agent: SubAgent | null
  toolOptions: { value: string; label: string }[]
  skillOptions: { value: string; label: string }[]
  onClose: () => void
  onSaved: () => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm()

  useEffect(() => {
    if (!open) return
    if (agent) form.setFieldsValue(agent)
    else {
      form.resetFields()
      form.setFieldsValue({
        risk_level: 'medium', version: '1.0.0', status: 'published',
        enabled: true, max_iterations: 6, business_domain: 'general',
      })
    }
  }, [open, agent, form])

  const submit = async () => {
    const values = await form.validateFields()
    try {
      if (agent) await agentApi.update(agent.id, values)
      else await agentApi.create(values)
      message.success(agent ? '已更新' : '注册成功')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <Modal
      title={agent ? `编辑子 Agent：${agent.name}` : '注册子 Agent'}
      open={open}
      onOk={submit}
      onCancel={onClose}
      width={820}
      okText="保存"
    >
      <Form form={form} layout="vertical">
        <Space align="start" wrap>
          <Form.Item name="name" label="名称（唯一标识）" rules={[{ required: true }]} style={{ width: 200 }}>
            <Input placeholder="hr_agent" disabled={!!agent} className="mono" />
          </Form.Item>
          <Form.Item name="display_name" label="显示名称" style={{ width: 200 }}>
            <Input placeholder="人事助手 Agent" />
          </Form.Item>
          <Form.Item name="business_domain" label="业务域" style={{ width: 130 }}>
            <Input placeholder="hr" />
          </Form.Item>
          <Form.Item name="risk_level" label="风险等级" style={{ width: 110 }}>
            <Select options={['low', 'medium', 'high'].map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item name="max_iterations" label="执行预算" style={{ width: 110 }}
                     tooltip="子 Agent 单次委派内允许的最大工具调用轮数">
            <InputNumber min={1} max={20} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="version" label="版本" style={{ width: 100 }}>
            <Input />
          </Form.Item>
        </Space>

        <Form.Item name="description" label="能力描述（主 Agent 路由依据，需含能力范围与输入输出规范）"
                   rules={[{ required: true, min: 20, message: '至少 20 字' }]}>
          <Input.TextArea rows={3} />
        </Form.Item>

        <Form.Item name="system_prompt" label="System Prompt">
          <Input.TextArea rows={3} placeholder="你是人事域业务 Agent，只处理…" />
        </Form.Item>

        <Form.Item name="tool_allowlist" label="MCP 工具 allowlist（最小必要工具集）">
          <Select mode="multiple" options={toolOptions} showSearch placeholder="选择该 Agent 可用的工具" />
        </Form.Item>

        <Form.Item name="skill_names" label="绑定 Skill">
          <Select mode="multiple" options={skillOptions} showSearch placeholder="选择可执行的标准 SOP" />
        </Form.Item>

        <Space align="start" style={{ display: 'flex' }}>
          <Form.Item name="positive_examples" label="正样例（必填，用于路由与 few-shot）"
                     rules={[{ required: true, message: '至少一条正样例' }]} style={{ flex: 1 }}>
            <Select mode="tags" placeholder="回车添加，如：E1001 是哪个部门的" />
          </Form.Item>
          <Form.Item name="negative_examples" label="负样例（抑制误路由）" style={{ flex: 1 }}>
            <Select mode="tags" placeholder="回车添加，如：VPN 连不上" />
          </Form.Item>
        </Space>

        <Form.Item name="required_scopes" label="所需权限 scope">
          <Select mode="tags" placeholder="hr:read / it:write …" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function EffectiveDrawer({ agent, onClose }: { agent: SubAgent | null; onClose: () => void }) {
  const [data, setData] = useState<any>(null)

  useEffect(() => {
    if (agent) agentApi.effective(agent.id).then(setData)
    else setData(null)
  }, [agent])

  return (
    <Drawer title={`运行时生效能力：${agent?.display_name || agent?.name}`} open={!!agent} onClose={onClose} width={600}>
      {data && (
        <>
          <Row gutter={12} style={{ marginBottom: 16 }}>
            <Col span={8}>
              <Card size="small"><Statistic title="平台工具总数" value={data.total_platform_tools} /></Card>
            </Col>
            <Col span={8}>
              <Card size="small"><Statistic title="本 Agent 生效" value={data.effective_tools.length}
                                            valueStyle={{ color: '#1677ff' }} /></Card>
            </Col>
            <Col span={8}>
              <Card size="small"><Statistic title="主 Agent 直接可见" value={data.supervisor_visible_tools} /></Card>
            </Col>
          </Row>

          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="声明的 allowlist">
              {data.declared_tools.map((t: string) => <Tag key={t} className="mono">{t}</Tag>)}
            </Descriptions.Item>
            <Descriptions.Item label="实际生效工具">
              {data.effective_tools.map((t: string) => <Tag key={t} color="blue" className="mono">{t}</Tag>)}
            </Descriptions.Item>
            <Descriptions.Item label="因权限被拒">
              {data.denied_by_permission.length
                ? data.denied_by_permission.map((t: string) => <Tag key={t} color="red">{t}</Tag>)
                : <span style={{ color: '#bbb' }}>无</span>}
            </Descriptions.Item>
            <Descriptions.Item label="未注册（悬空引用）">
              {data.missing_tools.length
                ? data.missing_tools.map((t: string) => <Tag key={t} color="orange">{t}</Tag>)
                : <span style={{ color: '#bbb' }}>无</span>}
            </Descriptions.Item>
            <Descriptions.Item label="绑定 Skill">
              {data.skills.length
                ? data.skills.map((s: string) => <Tag key={s} color="purple">{s}</Tag>)
                : <span style={{ color: '#bbb' }}>无</span>}
            </Descriptions.Item>
          </Descriptions>

          <Alert
            style={{ marginTop: 16 }}
            type="success"
            showIcon
            message="工具可见性收敛"
            description={`Skill 与子 Agent 认领了领域工具后，Supervisor 单次请求直接可见的工具从 ${data.total_platform_tools} 个收敛到 ${data.supervisor_visible_tools} 个，其余通过 delegate 按需使用。`}
          />
        </>
      )}
    </Drawer>
  )
}

import { useEffect, useState } from 'react'
import {
  App, Button, Card, Drawer, Empty, Form, Input, InputNumber, Modal, Popconfirm,
  Select, Space, Steps, Switch, Table, Tag, Timeline, Tooltip,
} from 'antd'
import { DeleteOutlined, EditOutlined, ExperimentOutlined, PlusOutlined } from '@ant-design/icons'

import { mcpApi, skillApi } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { MCPTool, Skill } from '@/types'

const RISK_COLOR: Record<string, string> = { low: 'green', medium: 'orange', high: 'red' }

export default function Skills() {
  const { message } = App.useApp()
  const { can } = useAuth()
  const [skills, setSkills] = useState<Skill[]>([])
  const [toolOptions, setToolOptions] = useState<{ value: string; label: string }[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<Skill | null>(null)
  const [creating, setCreating] = useState(false)
  const [dryRun, setDryRun] = useState<Skill | null>(null)

  const load = () => {
    setLoading(true)
    skillApi.list().then(setSkills).finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    mcpApi.servers().then((servers) => {
      const opts: { value: string; label: string }[] = []
      for (const s of servers) {
        for (const t of s.tools) {
          opts.push({ value: `${s.name}.${t.name}`, label: `${s.name}.${t.name} — ${t.description.slice(0, 30)}` })
        }
      }
      setToolOptions(opts)
    })
  }, [])

  const remove = async (id: string) => {
    try {
      await skillApi.remove(id)
      message.success('已删除')
      load()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>Skill 注册中心</h2>
          <p>把单个 MCP 工具或多工具编排沉淀为标准 SOP，步骤间可通过 <code className="mono">$steps.x.data.y</code> 传参</p>
        </div>
        {can('write') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            注册 Skill
          </Button>
        )}
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={skills}
          pagination={false}
          expandable={{
            expandedRowRender: (skill) => (
              <div style={{ padding: '8px 16px' }}>
                <Steps
                  size="small"
                  direction="vertical"
                  current={-1}
                  items={(skill.steps || []).map((step) => ({
                    title: (
                      <Space>
                        <span className="mono">{step.id}</span>
                        <Tag color={step.type === 'tool' ? 'blue' : 'purple'}>{step.type}</Tag>
                        {step.tool && <span className="mono">{step.tool}</span>}
                        {step.optional && <Tag>可选</Tag>}
                      </Space>
                    ),
                    description: (
                      <div style={{ fontSize: 12 }}>
                        {step.description}
                        {step.input_map && Object.keys(step.input_map).length > 0 && (
                          <div className="mono" style={{ color: '#8c8c8c' }}>
                            参数映射：{JSON.stringify(step.input_map)}
                          </div>
                        )}
                        {step.args && Object.keys(step.args).length > 0 && (
                          <div className="mono" style={{ color: '#8c8c8c' }}>
                            固定入参：{JSON.stringify(step.args)}
                          </div>
                        )}
                      </div>
                    ),
                  }))}
                />
                {skill.examples?.length > 0 && (
                  <div style={{ marginTop: 8, fontSize: 12 }}>
                    <b>正样例：</b>
                    {skill.examples.map((e) => <Tag key={e} color="green">{e}</Tag>)}
                  </div>
                )}
                {skill.negative_examples?.length > 0 && (
                  <div style={{ marginTop: 4, fontSize: 12 }}>
                    <b>负样例：</b>
                    {skill.negative_examples.map((e) => <Tag key={e} color="red">{e}</Tag>)}
                  </div>
                )}
              </div>
            ),
          }}
          columns={[
            {
              title: 'Skill',
              dataIndex: 'name',
              render: (v: string, row: Skill) => (
                <div>
                  <div style={{ fontWeight: 600 }}>{row.display_name || v}</div>
                  <div className="mono" style={{ color: '#8c8c8c' }}>{v}</div>
                </div>
              ),
            },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            { title: '业务域', dataIndex: 'business_domain', width: 90, render: (v) => <Tag>{v}</Tag> },
            { title: '步骤', width: 70, render: (_: any, row: Skill) => row.steps?.length ?? 0 },
            {
              title: '风险',
              dataIndex: 'risk_level',
              width: 80,
              render: (v: string) => <Tag color={RISK_COLOR[v]}>{v}</Tag>,
            },
            { title: '版本', dataIndex: 'version', width: 80 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (v: string, row: Skill) => (
                <Tag color={v === 'published' && row.enabled ? 'green' : 'default'}>
                  {!row.enabled ? '已停用' : v === 'published' ? '已发布' : v}
                </Tag>
              ),
            },
            { title: 'Owner', dataIndex: 'owner', width: 90 },
            {
              title: '操作',
              width: 170,
              render: (_: any, row: Skill) => (
                <Space size={4}>
                  <Button size="small" icon={<ExperimentOutlined />} onClick={() => setDryRun(row)}>试运行</Button>
                  {can('write') && (
                    <>
                      <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(row)} />
                      <Popconfirm title="确认删除该 Skill？" onConfirm={() => remove(row.id)}>
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

      <SkillForm
        open={creating || !!editing}
        skill={editing}
        toolOptions={toolOptions}
        onClose={() => {
          setCreating(false)
          setEditing(null)
        }}
        onSaved={() => {
          setCreating(false)
          setEditing(null)
          load()
        }}
      />

      <DryRunDrawer skill={dryRun} onClose={() => setDryRun(null)} />
    </>
  )
}

// --------------------------------------------------------------------------- 表单

function SkillForm({ open, skill, toolOptions, onClose, onSaved }: {
  open: boolean
  skill: Skill | null
  toolOptions: { value: string; label: string }[]
  onClose: () => void
  onSaved: () => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm()

  useEffect(() => {
    if (!open) return
    if (skill) {
      form.setFieldsValue({
        ...skill,
        input_required: (skill.input_schema?.required ?? []).join(','),
      })
    } else {
      form.resetFields()
      form.setFieldsValue({ risk_level: 'low', version: '1.0.0', status: 'published', enabled: true, steps: [] })
    }
  }, [open, skill, form])

  const submit = async () => {
    const values = await form.validateFields()
    const required: string[] = (values.input_required || '')
      .split(',').map((s: string) => s.trim()).filter(Boolean)

    const payload = {
      ...values,
      input_schema: {
        type: 'object',
        properties: Object.fromEntries(required.map((k) => [k, { type: 'string' }])),
        required,
      },
      steps: (values.steps || []).map((s: any, i: number) => ({
        ...s,
        id: s.id || `step${i + 1}`,
        args: s.args ? safeJson(s.args) : {},
        input_map: s.input_map ? safeJson(s.input_map) : {},
      })),
    }
    delete (payload as any).input_required

    try {
      if (skill) await skillApi.update(skill.id, payload)
      else await skillApi.create(payload)
      message.success(skill ? '已更新' : '注册成功')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <Modal
      title={skill ? `编辑 Skill：${skill.name}` : '注册 Skill'}
      open={open}
      onOk={submit}
      onCancel={onClose}
      width={860}
      okText="保存"
    >
      <Form form={form} layout="vertical">
        <Space style={{ display: 'flex' }} align="start">
          <Form.Item name="name" label="名称（唯一标识）" rules={[{ required: true }]} style={{ width: 220 }}>
            <Input placeholder="expense_precheck" disabled={!!skill} className="mono" />
          </Form.Item>
          <Form.Item name="display_name" label="显示名称" style={{ width: 220 }}>
            <Input placeholder="报销合规预检" />
          </Form.Item>
          <Form.Item name="business_domain" label="业务域" style={{ width: 140 }}>
            <Input placeholder="finance" />
          </Form.Item>
          <Form.Item name="risk_level" label="风险等级" style={{ width: 120 }}>
            <Select options={[
              { value: 'low', label: 'low' },
              { value: 'medium', label: 'medium' },
              { value: 'high', label: 'high（需审批）' },
            ]} />
          </Form.Item>
          <Form.Item name="version" label="版本" style={{ width: 100 }}>
            <Input />
          </Form.Item>
        </Space>

        <Form.Item name="description" label="描述（路由依据，需说明适用场景）" rules={[{ required: true }]}>
          <Input.TextArea rows={2} />
        </Form.Item>

        <Form.Item name="input_required" label="必填入参（逗号分隔）"
                   extra="步骤中可用 $input.xxx 引用这些参数">
          <Input placeholder="employee_id, expense_type" className="mono" />
        </Form.Item>

        <Form.List name="steps">
          {(fields, { add, remove }) => (
            <>
              <div style={{ marginBottom: 8, fontWeight: 500 }}>执行步骤</div>
              {fields.map((field) => (
                <Card key={field.key} size="small" style={{ marginBottom: 8 }}
                      extra={<Button size="small" danger onClick={() => remove(field.name)}>移除</Button>}>
                  <Space align="start" wrap>
                    <Form.Item name={[field.name, 'id']} label="步骤 ID" style={{ width: 120, marginBottom: 8 }}>
                      <Input placeholder="step1" className="mono" />
                    </Form.Item>
                    <Form.Item name={[field.name, 'type']} label="类型" style={{ width: 110, marginBottom: 8 }}
                               initialValue="tool">
                      <Select options={[{ value: 'tool', label: 'MCP 工具' }, { value: 'llm', label: 'LLM 分析' }]} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'tool']} label="工具" style={{ width: 280, marginBottom: 8 }}>
                      <Select showSearch options={toolOptions} placeholder="选择已注册工具" allowClear />
                    </Form.Item>
                    <Form.Item name={[field.name, 'optional']} label="可选步骤" valuePropName="checked"
                               style={{ marginBottom: 8 }}>
                      <Switch size="small" />
                    </Form.Item>
                  </Space>
                  <Form.Item name={[field.name, 'description']} label="步骤说明" style={{ marginBottom: 8 }}>
                    <Input />
                  </Form.Item>
                  <Space align="start" style={{ display: 'flex' }}>
                    <Form.Item name={[field.name, 'input_map']} label="参数映射 JSON" style={{ flex: 1, marginBottom: 0 }}
                               extra='如 {"employee_id": "$input.employee_id"}'
                               getValueProps={(v) => ({ value: typeof v === 'string' ? v : JSON.stringify(v ?? {}) })}>
                      <Input.TextArea rows={2} className="mono" />
                    </Form.Item>
                    <Form.Item name={[field.name, 'args']} label="固定入参 JSON" style={{ flex: 1, marginBottom: 0 }}
                               extra='如 {"limit": 3}'
                               getValueProps={(v) => ({ value: typeof v === 'string' ? v : JSON.stringify(v ?? {}) })}>
                      <Input.TextArea rows={2} className="mono" />
                    </Form.Item>
                  </Space>
                </Card>
              ))}
              <Button block onClick={() => add({ type: 'tool' })} icon={<PlusOutlined />}>添加步骤</Button>
            </>
          )}
        </Form.List>

        <Form.Item name="output_template" label="输出模板（可选）" style={{ marginTop: 16 }}
                   extra="支持 {{$steps.step1.data.name}} 变量替换">
          <Input.TextArea rows={2} className="mono" />
        </Form.Item>

        <Space align="start" style={{ display: 'flex' }}>
          <Form.Item name="examples" label="正样例（路由匹配）" style={{ flex: 1 }}
                     getValueFromEvent={(v) => v}>
            <Select mode="tags" placeholder="回车添加，如：报销差旅费有什么标准" />
          </Form.Item>
          <Form.Item name="negative_examples" label="负样例（抑制误路由）" style={{ flex: 1 }}>
            <Select mode="tags" placeholder="回车添加" />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  )
}

function safeJson(value: any): Record<string, any> {
  if (typeof value !== 'string') return value ?? {}
  try {
    return JSON.parse(value)
  } catch {
    return {}
  }
}

// --------------------------------------------------------------------------- 试运行

function DryRunDrawer({ skill, onClose }: { skill: Skill | null; onClose: () => void }) {
  const { message } = App.useApp()
  const [inputs, setInputs] = useState('{}')
  const [result, setResult] = useState<any>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (skill) {
      const required = skill.input_schema?.required ?? []
      setInputs(JSON.stringify(Object.fromEntries(required.map((k: string) => [k, ''])), null, 2))
      setResult(null)
    }
  }, [skill])

  const run = async () => {
    if (!skill) return
    setLoading(true)
    try {
      setResult(await skillApi.dryRun(skill.id, safeJson(inputs)))
    } catch (e: any) {
      message.error(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <Drawer title={`试运行：${skill?.display_name || skill?.name}`} open={!!skill} onClose={onClose} width={680}>
      <Input.TextArea rows={5} value={inputs} onChange={(e) => setInputs(e.target.value)} className="mono" />
      <Button type="primary" loading={loading} onClick={run} style={{ marginTop: 12 }}>
        按步骤执行
      </Button>

      {result && (
        <div style={{ marginTop: 20 }}>
          <Timeline
            items={(result.steps || []).map((s: any) => ({
              color: s.skipped ? 'gray' : s.success ? 'green' : 'red',
              children: (
                <div>
                  <Space>
                    <b className="mono">{s.step}</b>
                    {s.tool && <Tag>{s.tool}</Tag>}
                    {s.latency_ms !== undefined && <Tag>{s.latency_ms}ms</Tag>}
                  </Space>
                  {s.args && (
                    <div className="mono" style={{ fontSize: 11.5, color: '#8c8c8c' }}>
                      入参 {JSON.stringify(s.args)}
                    </div>
                  )}
                  <div className="code-block" style={{ marginTop: 6 }}>
                    {JSON.stringify(s.data ?? s.error ?? s.message, null, 2)}
                  </div>
                </div>
              ),
            }))}
          />
          {result.output && (
            <Card size="small" title="输出模板渲染结果">{result.output}</Card>
          )}
        </div>
      )}
      {!result && <Empty style={{ marginTop: 40 }} image={Empty.PRESENTED_IMAGE_SIMPLE} description="填写入参后执行" />}
    </Drawer>
  )
}

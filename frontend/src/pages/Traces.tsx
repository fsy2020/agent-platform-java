import { useEffect, useState } from 'react'
import { Button, Card, Col, Descriptions, Drawer, Row, Select, Space, Statistic, Table, Tag, Timeline } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

import { traceApi } from '@/api/client'
import type { Trace } from '@/types'

const ROUTE_COLOR: Record<string, string> = {
  agent: 'blue', skill: 'purple', tool: 'cyan', answer: 'default', clarify: 'gold',
}

const SPAN_COLOR: Record<string, string> = {
  context: 'gray', route: 'blue', tool: 'green', skill: 'purple',
  agent: 'blue', llm: 'cyan', guard: 'orange', approval: 'red',
}

export default function Traces() {
  const [rows, setRows] = useState<Trace[]>([])
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState<string | undefined>()
  const [routeType, setRouteType] = useState<string | undefined>()
  const [detail, setDetail] = useState<Trace | null>(null)

  const load = () => {
    setLoading(true)
    traceApi.list({ status, route_type: routeType, limit: 100 })
      .then(setRows)
      .finally(() => setLoading(false))
  }

  useEffect(load, [status, routeType])

  const openDetail = async (id: string) => setDetail(await traceApi.detail(id))

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>全链路观测</h2>
          <p>按 turn 记录路由决策、工具链路、Token 用量、上下文占用与异常恢复动作，支持轨迹回放定位根因</p>
        </div>
        <Space>
          <Select allowClear placeholder="状态" style={{ width: 130 }} value={status} onChange={setStatus}
                  options={[
                    { value: 'success', label: '成功' },
                    { value: 'blocked', label: '被护栏阻断' },
                    { value: 'interrupted', label: '审批中断' },
                    { value: 'failed', label: '失败' },
                  ]} />
          <Select allowClear placeholder="执行路径" style={{ width: 150 }} value={routeType} onChange={setRouteType}
                  options={[
                    { value: 'agent', label: '分发子 Agent' },
                    { value: 'skill', label: '执行 Skill' },
                    { value: 'tool', label: '直接调用工具' },
                    { value: 'answer', label: '直接回答' },
                    { value: 'clarify', label: '澄清追问' },
                  ]} />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          pagination={{ pageSize: 20 }}
          onRow={(row) => ({ onClick: () => openDetail(row.id), style: { cursor: 'pointer' } })}
          columns={[
            {
              title: '时间',
              dataIndex: 'created_at',
              width: 150,
              render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
            },
            { title: '用户请求', dataIndex: 'question', ellipsis: true },
            {
              title: '执行路径',
              width: 190,
              render: (_: any, row: Trace) => (
                <Space size={4}>
                  <Tag color={ROUTE_COLOR[row.route_type] ?? 'default'}>{row.route_type || '-'}</Tag>
                  <span className="mono" style={{ fontSize: 12 }}>{row.route_target}</span>
                </Space>
              ),
            },
            {
              title: '置信度',
              dataIndex: 'route_confidence',
              width: 90,
              render: (v: number) =>
                v ? <Tag color={v >= 0.7 ? 'green' : 'orange'}>{v.toFixed(2)}</Tag> : '-',
            },
            {
              title: '工具',
              width: 90,
              render: (_: any, row: Trace) => (
                <span>
                  {row.tool_calls}
                  {row.tool_failures > 0 && <Tag color="red" style={{ marginLeft: 4 }}>{row.tool_failures} 失败</Tag>}
                </span>
              ),
            },
            { title: '可见工具', dataIndex: 'visible_tool_count', width: 90,
              render: (v: number, row: Trace) => `${v} / ${row.total_tool_count}` },
            { title: '耗时', dataIndex: 'latency_ms', width: 90, render: (v: number) => `${v}ms` },
            { title: 'Token', dataIndex: 'total_tokens', width: 90 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (v: string, row: Trace) => (
                <Space size={2}>
                  <Tag color={v === 'success' ? 'green' : v === 'interrupted' ? 'purple' : 'red'}>{v}</Tag>
                  {row.degraded_to_rules && <Tag color="orange">降级</Tag>}
                  {row.context_compressed && <Tag color="blue">压缩</Tag>}
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Drawer title="Trace 回放" open={!!detail} onClose={() => setDetail(null)} width={780}>
        {detail && (
          <>
            <Row gutter={8} style={{ marginBottom: 16 }}>
              <Col span={6}><Card size="small"><Statistic title="耗时" value={detail.latency_ms} suffix="ms" /></Card></Col>
              <Col span={6}><Card size="small"><Statistic title="迭代" value={detail.iterations} suffix="轮" /></Card></Col>
              <Col span={6}><Card size="small"><Statistic title="工具调用" value={detail.tool_calls} /></Card></Col>
              <Col span={6}><Card size="small"><Statistic title="Token" value={detail.total_tokens} /></Card></Col>
            </Row>

            <Descriptions column={2} bordered size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="用户请求" span={2}>{detail.question}</Descriptions.Item>
              <Descriptions.Item label="执行路径">
                <Tag color={ROUTE_COLOR[detail.route_type]}>{detail.route_type}</Tag>
                <span className="mono">{detail.route_target}</span>
              </Descriptions.Item>
              <Descriptions.Item label="路由置信度">{detail.route_confidence?.toFixed(2)}</Descriptions.Item>
              <Descriptions.Item label="路由理由" span={2}>{detail.route_reason || '-'}</Descriptions.Item>
              <Descriptions.Item label="模型">
                {detail.model_used}
                {detail.model_fallback && <Tag color="orange" style={{ marginLeft: 4 }}>已切备用</Tag>}
                {detail.degraded_to_rules && <Tag color="orange" style={{ marginLeft: 4 }}>规则引擎降级</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="上下文">
                {detail.context_compressed ? <Tag color="blue">已压缩</Tag> : '未压缩'}
              </Descriptions.Item>
              <Descriptions.Item label="可见工具收敛" span={2}>
                {detail.visible_tool_count} / {detail.total_tool_count}
              </Descriptions.Item>
              <Descriptions.Item label="最终答复" span={2}>
                <div style={{ whiteSpace: 'pre-wrap' }}>{detail.answer || '-'}</div>
              </Descriptions.Item>
            </Descriptions>

            <h4>执行 Span</h4>
            <Timeline
              items={(detail.spans || []).map((span) => ({
                color: span.status === 'success' ? (SPAN_COLOR[span.span_type] ?? 'blue') : 'red',
                children: (
                  <div>
                    <Space>
                      <b>{span.name}</b>
                      <Tag>{span.span_type}</Tag>
                      {span.latency_ms > 0 && <Tag>{span.latency_ms}ms</Tag>}
                      {span.status !== 'success' && <Tag color="red">{span.status}</Tag>}
                    </Space>
                    {span.error && <div style={{ color: '#ff4d4f', fontSize: 12 }}>{span.error}</div>}
                    {Object.keys(span.input || {}).length > 0 && (
                      <div className="code-block" style={{ marginTop: 4 }}>
                        入参 {JSON.stringify(span.input, null, 2)}
                      </div>
                    )}
                    {Object.keys(span.output || {}).length > 0 && (
                      <div className="code-block" style={{ marginTop: 4 }}>
                        输出 {JSON.stringify(span.output, null, 2)}
                      </div>
                    )}
                  </div>
                ),
              }))}
            />
          </>
        )}
      </Drawer>
    </>
  )
}

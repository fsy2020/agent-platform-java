import { useCallback, useEffect, useRef, useState } from 'react'
import {
  App, Button, Card, Col, Descriptions, Drawer, Empty, Form, Input, Modal, Progress,
  Row, Segmented, Select, Space, Statistic, Table, Tag,
} from 'antd'
import { CheckOutlined, CloseOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

import { evalApi } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { EvalDataset, EvalResult, EvalRun } from '@/types'

export default function Evaluation() {
  const { message } = App.useApp()
  const { can } = useAuth()
  const [datasets, setDatasets] = useState<EvalDataset[]>([])
  const [datasetId, setDatasetId] = useState<string | undefined>()
  const [runs, setRuns] = useState<EvalRun[]>([])
  const [activeRun, setActiveRun] = useState<EvalRun | null>(null)
  const [results, setResults] = useState<EvalResult[]>([])
  const [onlyFailed, setOnlyFailed] = useState(false)
  const [caseModal, setCaseModal] = useState(false)
  const [labeling, setLabeling] = useState<EvalResult | null>(null)
  const pollRef = useRef<number | null>(null)

  const loadDatasets = useCallback(async () => {
    const rows = await evalApi.datasets()
    setDatasets(rows)
    if (!datasetId && rows.length) setDatasetId(rows[0].id)
  }, [datasetId])

  useEffect(() => { loadDatasets() }, [loadDatasets])

  const loadRuns = useCallback(() => {
    if (!datasetId) return
    evalApi.runs(datasetId).then(setRuns)
  }, [datasetId])

  useEffect(() => { loadRuns() }, [loadRuns])

  // 跑批进行中时轮询进度
  useEffect(() => {
    if (!activeRun || activeRun.status !== 'running') {
      if (pollRef.current) window.clearInterval(pollRef.current)
      return
    }
    pollRef.current = window.setInterval(async () => {
      const fresh = await evalApi.run(activeRun.id)
      setActiveRun(fresh)
      if (fresh.status !== 'running') {
        loadRuns()
        setResults(await evalApi.results(fresh.id))
      }
    }, 1500)
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current)
    }
  }, [activeRun, loadRuns])

  const dataset = datasets.find((d) => d.id === datasetId)

  const startRun = async () => {
    if (!datasetId) return
    try {
      const run = await evalApi.startRun(datasetId)
      message.success('跑批已启动')
      setActiveRun(run)
      setResults([])
      loadRuns()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  const openRun = async (run: EvalRun) => {
    setActiveRun(run)
    setResults(await evalApi.results(run.id, onlyFailed))
  }

  const metrics = activeRun?.metrics ?? {}

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>评测平台</h2>
          <p>黄金测试集跑批 + LLM 预判 + 人工抽检；统计 Agent 选择准确率、工具成功率、端到端质量与成本</p>
        </div>
        <Space>
          <Select style={{ width: 240 }} value={datasetId} onChange={setDatasetId}
                  options={datasets.map((d) => ({ value: d.id, label: `${d.name}（${d.cases.length} 例）` }))} />
          {can('write') && (
            <>
              <Button icon={<PlusOutlined />} onClick={() => setCaseModal(true)}>补充用例</Button>
              <Button type="primary" icon={<PlayCircleOutlined />} onClick={startRun} disabled={!datasetId}>
                发起跑批
              </Button>
            </>
          )}
        </Space>
      </div>

      <Row gutter={12}>
        <Col span={8}>
          <Card size="small" title="跑批记录" extra={<Button size="small" type="text" icon={<ReloadOutlined />} onClick={loadRuns} />}>
            <Table
              rowKey="id"
              size="small"
              pagination={false}
              dataSource={runs}
              locale={{ emptyText: '暂无跑批' }}
              onRow={(row) => ({ onClick: () => openRun(row), style: { cursor: 'pointer' } })}
              rowClassName={(row) => (row.id === activeRun?.id ? 'ant-table-row-selected' : '')}
              columns={[
                {
                  title: '时间',
                  dataIndex: 'created_at',
                  render: (v: string) => dayjs(v).format('MM-DD HH:mm'),
                },
                {
                  title: '进度',
                  render: (_: any, row: EvalRun) =>
                    row.status === 'running'
                      ? <Progress percent={Math.round((row.finished / Math.max(row.total, 1)) * 100)} size="small" />
                      : <Tag color={row.status === 'finished' ? 'green' : 'red'}>{row.status}</Tag>,
                },
                {
                  title: '路由准确率',
                  render: (_: any, row: EvalRun) =>
                    row.metrics?.route_accuracy != null
                      ? `${(row.metrics.route_accuracy * 100).toFixed(0)}%`
                      : '-',
                },
              ]}
            />
          </Card>

          <Card size="small" title="数据集用例" style={{ marginTop: 12 }}>
            <Table
              rowKey="id"
              size="small"
              pagination={{ pageSize: 8 }}
              dataSource={dataset?.cases ?? []}
              columns={[
                { title: '问题', dataIndex: 'question', ellipsis: true },
                {
                  title: '期望路径',
                  width: 130,
                  render: (_: any, row: any) => (
                    <span className="mono" style={{ fontSize: 11.5 }}>
                      {row.expected_route_type}{row.expected_target ? `:${row.expected_target}` : ''}
                    </span>
                  ),
                },
              ]}
            />
          </Card>
        </Col>

        <Col span={16}>
          {activeRun ? (
            <>
              <Card size="small" title={`跑批指标 · ${dayjs(activeRun.created_at).format('MM-DD HH:mm')}`}>
                {activeRun.status === 'running' ? (
                  <Progress percent={Math.round((activeRun.finished / Math.max(activeRun.total, 1)) * 100)}
                            status="active" />
                ) : (
                  <Row gutter={12}>
                    <Col span={4}><Statistic title="用例数" value={metrics.total ?? 0} /></Col>
                    <Col span={5}>
                      <Statistic title="Agent 选择准确率" precision={1} suffix="%"
                                 value={(metrics.route_accuracy ?? 0) * 100}
                                 valueStyle={{ color: (metrics.route_accuracy ?? 0) >= 0.9 ? '#52c41a' : '#faad14' }} />
                    </Col>
                    <Col span={5}>
                      <Statistic title="工具成功率" precision={1} suffix="%"
                                 value={(metrics.tool_success_rate ?? 0) * 100} />
                    </Col>
                    <Col span={4}>
                      <Statistic title="平均得分" precision={2}
                                 value={metrics.avg_score ?? 0} />
                    </Col>
                    <Col span={3}><Statistic title="平均时延" value={metrics.avg_latency_ms ?? 0} suffix="ms" /></Col>
                    <Col span={3}><Statistic title="Token" value={metrics.total_tokens ?? 0} /></Col>
                  </Row>
                )}
              </Card>

              <Card
                size="small"
                title="结果明细"
                style={{ marginTop: 12 }}
                extra={
                  <Segmented
                    size="small"
                    value={onlyFailed ? 'failed' : 'all'}
                    onChange={async (v) => {
                      const failed = v === 'failed'
                      setOnlyFailed(failed)
                      setResults(await evalApi.results(activeRun.id, failed))
                    }}
                    options={[{ label: '全部', value: 'all' }, { label: '仅 bad case', value: 'failed' }]}
                  />
                }
              >
                <Table
                  rowKey="id"
                  size="small"
                  dataSource={results}
                  pagination={{ pageSize: 10 }}
                  onRow={(row) => ({ onClick: () => setLabeling(row), style: { cursor: 'pointer' } })}
                  columns={[
                    { title: '问题', dataIndex: 'question', ellipsis: true },
                    {
                      title: '期望 → 实际',
                      width: 220,
                      render: (_: any, row: EvalResult) => (
                        <span className="mono" style={{ fontSize: 11.5 }}>
                          {row.expected_route_type}:{row.expected_target || '-'}
                          {' → '}
                          <b style={{ color: row.route_correct ? '#52c41a' : '#ff4d4f' }}>
                            {row.actual_route_type}:{row.actual_target || '-'}
                          </b>
                        </span>
                      ),
                    },
                    {
                      title: '路由',
                      width: 70,
                      render: (_: any, row: EvalResult) =>
                        row.route_correct ? <Tag color="green">✓</Tag> : <Tag color="red">✗</Tag>,
                    },
                    {
                      title: 'LLM 分',
                      dataIndex: 'llm_score',
                      width: 80,
                      render: (v: number | null) =>
                        v === null ? <Tag>待评</Tag>
                          : <Tag color={v >= 0.7 ? 'green' : v >= 0.4 ? 'orange' : 'red'}>{v.toFixed(2)}</Tag>,
                    },
                    {
                      title: '人工',
                      dataIndex: 'human_label',
                      width: 80,
                      render: (v: string) =>
                        v ? <Tag color={v === 'pass' ? 'green' : 'red'}>{v}</Tag> : <span style={{ color: '#bbb' }}>-</span>,
                    },
                    { title: '耗时', dataIndex: 'latency_ms', width: 80, render: (v) => `${v}ms` },
                  ]}
                />
              </Card>
            </>
          ) : (
            <Card size="small">
              <Empty description="选择左侧跑批记录查看指标，或发起一次新的跑批" style={{ padding: '60px 0' }} />
            </Card>
          )}
        </Col>
      </Row>

      <AddCaseModal
        open={caseModal}
        datasetId={datasetId}
        onClose={() => setCaseModal(false)}
        onSaved={() => { setCaseModal(false); loadDatasets() }}
      />

      <LabelDrawer
        result={labeling}
        onClose={() => setLabeling(null)}
        onSaved={async () => {
          if (activeRun) setResults(await evalApi.results(activeRun.id, onlyFailed))
          setLabeling(null)
        }}
      />
    </>
  )
}

function AddCaseModal({ open, datasetId, onClose, onSaved }: {
  open: boolean; datasetId?: string; onClose: () => void; onSaved: () => void
}) {
  const { message } = App.useApp()
  const [form] = Form.useForm()

  const submit = async () => {
    if (!datasetId) return
    const values = await form.validateFields()
    try {
      await evalApi.addCase(datasetId, values)
      message.success('已加入黄金测试集')
      form.resetFields()
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <Modal title="补充测试用例（bad case 回流）" open={open} onOk={submit} onCancel={onClose} okText="加入">
      <Form form={form} layout="vertical">
        <Form.Item name="question" label="用户问题" rules={[{ required: true }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        <Space style={{ display: 'flex' }} align="start">
          <Form.Item name="expected_route_type" label="期望执行路径" style={{ width: 180 }}>
            <Select allowClear options={['agent', 'skill', 'tool', 'answer', 'clarify'].map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item name="expected_target" label="期望目标" style={{ width: 220 }}>
            <Input placeholder="hr_agent / customer_360 …" className="mono" />
          </Form.Item>
        </Space>
        <Form.Item name="expected_answer" label="参考答案关键词（用于评分）">
          <Input.TextArea rows={2} placeholder="AI大模型事业部 范远" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function LabelDrawer({ result, onClose, onSaved }: {
  result: EvalResult | null; onClose: () => void; onSaved: () => void
}) {
  const { message } = App.useApp()
  const [comment, setComment] = useState('')

  useEffect(() => setComment(result?.human_comment ?? ''), [result])

  const label = async (value: 'pass' | 'fail') => {
    if (!result) return
    try {
      await evalApi.label(result.id, value, comment)
      message.success('已记录人工标注')
      onSaved()
    } catch (e: any) {
      message.error(e.message)
    }
  }

  return (
    <Drawer title="用例结果与人工抽检" open={!!result} onClose={onClose} width={640}>
      {result && (
        <>
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="问题">{result.question}</Descriptions.Item>
            <Descriptions.Item label="期望路径">
              <span className="mono">{result.expected_route_type}:{result.expected_target || '-'}</span>
            </Descriptions.Item>
            <Descriptions.Item label="实际路径">
              <span className="mono">{result.actual_route_type}:{result.actual_target || '-'}</span>
              {result.route_correct ? <Tag color="green" style={{ marginLeft: 6 }}>匹配</Tag>
                                    : <Tag color="red" style={{ marginLeft: 6 }}>不匹配</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="实际答复">
              <div style={{ whiteSpace: 'pre-wrap' }}>{result.answer || '-'}</div>
            </Descriptions.Item>
            <Descriptions.Item label="LLM 评分">
              {result.llm_score === null ? '待评' : result.llm_score.toFixed(2)} · {result.llm_comment}
            </Descriptions.Item>
            <Descriptions.Item label="Trace"><span className="mono">{result.trace_id}</span></Descriptions.Item>
          </Descriptions>

          <div style={{ marginTop: 16 }}>
            <Input.TextArea rows={3} value={comment} onChange={(e) => setComment(e.target.value)}
                            placeholder="人工抽检意见（不合格的 case 建议补充到 few-shot 样例库）" />
            <Space style={{ marginTop: 12 }}>
              <Button type="primary" icon={<CheckOutlined />} onClick={() => label('pass')}>标记通过</Button>
              <Button danger icon={<CloseOutlined />} onClick={() => label('fail')}>标记不通过</Button>
            </Space>
          </div>
        </>
      )}
    </Drawer>
  )
}

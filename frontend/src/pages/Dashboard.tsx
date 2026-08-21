import { useEffect, useState } from 'react'
import { Alert, Card, Col, Empty, Progress, Row, Segmented, Spin, Statistic, Table, Tag } from 'antd'
import ReactECharts from 'echarts-for-react'
import { useNavigate } from 'react-router-dom'

import { traceApi } from '@/api/client'
import type { Overview } from '@/types'

const ROUTE_LABEL: Record<string, string> = {
  agent: '分发子 Agent',
  skill: '执行 Skill',
  tool: '直接调用工具',
  answer: '直接回答',
  clarify: '澄清追问',
  unknown: '未分类',
}

export default function Dashboard() {
  const navigate = useNavigate()
  const [days, setDays] = useState(7)
  const [data, setData] = useState<Overview | null>(null)
  const [tools, setTools] = useState<any[]>([])
  const [caps, setCaps] = useState<any>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    Promise.all([traceApi.overview(days), traceApi.toolMetrics(), traceApi.capabilityMetrics()])
      .then(([o, t, c]) => {
        setData(o)
        setTools(t)
        setCaps(c)
      })
      .finally(() => setLoading(false))
  }, [days])

  if (loading || !data) return <Spin size="large" style={{ display: 'block', margin: '80px auto' }} />

  const convergence = data.assets.tools
    ? Math.round((1 - data.avg_visible_tools / Math.max(data.assets.tools, 1)) * 100)
    : 0

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>平台总览</h2>
          <p>Supervisor + 子 Agent/Skill Registry + MCP Gateway 的运行时指标与成本归因</p>
        </div>
        <Segmented
          value={days}
          onChange={(v) => setDays(v as number)}
          options={[
            { label: '近 1 天', value: 1 },
            { label: '近 7 天', value: 7 },
            { label: '近 30 天', value: 30 },
          ]}
        />
      </div>

      <Row gutter={[12, 12]}>
        {[
          { title: 'MCP Server', value: data.assets.servers, suffix: '个' },
          { title: 'MCP 工具', value: data.assets.tools, suffix: '个' },
          { title: 'Skill', value: data.assets.skills, suffix: '个' },
          { title: '子 Agent', value: data.assets.agents, suffix: '个' },
        ].map((item) => (
          <Col span={6} key={item.title}>
            <Card className="stat-card" size="small">
              <Statistic title={item.title} value={item.value} suffix={item.suffix} />
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col span={6}>
          <Card className="stat-card" size="small">
            <Statistic title="对话轮次" value={data.total_turns} suffix="turn" />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="stat-card" size="small">
            <Statistic
              title="任务成功率"
              value={data.success_rate * 100}
              precision={1}
              suffix="%"
              valueStyle={{ color: data.success_rate >= 0.9 ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="stat-card" size="small">
            <Statistic
              title="工具调用成功率"
              value={data.tool_success_rate * 100}
              precision={1}
              suffix="%"
              valueStyle={{ color: data.tool_success_rate >= 0.95 ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="stat-card" size="small">
            <Statistic title="平均时延" value={data.avg_latency_ms} suffix="ms" />
          </Card>
        </Col>
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col span={8}>
          <Card size="small" title="工具可见性收敛">
            <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
              <Progress
                type="dashboard"
                percent={Math.max(convergence, 0)}
                size={110}
                format={(p) => <span style={{ fontSize: 18 }}>{p}%</span>}
              />
              <div style={{ fontSize: 13, lineHeight: 2 }}>
                <div>
                  平台工具总数：<b>{data.assets.tools}</b>
                </div>
                <div>
                  单次请求平均可见：<b style={{ color: '#1677ff' }}>{data.avg_visible_tools}</b>
                </div>
                <div style={{ color: '#8c8c8c', fontSize: 12 }}>
                  分层路由避免 Prompt 膨胀与工具误选
                </div>
              </div>
            </div>
          </Card>
        </Col>

        <Col span={8}>
          <Card size="small" title="执行路径分布">
            {data.route_distribution.length ? (
              <ReactECharts
                style={{ height: 190 }}
                option={{
                  tooltip: { trigger: 'item' },
                  legend: { bottom: 0, itemWidth: 10, itemHeight: 10, textStyle: { fontSize: 11 } },
                  series: [
                    {
                      type: 'pie',
                      radius: ['42%', '66%'],
                      center: ['50%', '43%'],
                      label: { show: false },
                      data: data.route_distribution.map((r) => ({
                        name: ROUTE_LABEL[r.route_type] ?? r.route_type,
                        value: r.count,
                      })),
                    },
                  ],
                }}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
            )}
          </Card>
        </Col>

        <Col span={8}>
          <Card size="small" title="路由质量">
            <Row gutter={12}>
              <Col span={12}>
                <Statistic
                  title="平均路由置信度"
                  value={(caps?.avg_route_confidence ?? 0) * 100}
                  precision={1}
                  suffix="%"
                />
              </Col>
              <Col span={12}>
                <Statistic
                  title="低置信度路由"
                  value={caps?.low_confidence_routes ?? 0}
                  suffix="次"
                  valueStyle={{ color: (caps?.low_confidence_routes ?? 0) > 0 ? '#faad14' : undefined }}
                />
              </Col>
            </Row>
            <div style={{ marginTop: 10, fontSize: 12, color: '#8c8c8c' }}>
              低置信度样本会回流到 few-shot 样例库与 description 优化
            </div>
            <div style={{ marginTop: 8 }}>
              <Statistic title="上下文压缩触发率" value={data.compression_rate * 100} precision={1} suffix="%" />
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[12, 12]} style={{ marginTop: 12 }}>
        <Col span={14}>
          <Card size="small" title="每日调用量与时延">
            {data.daily.length ? (
              <ReactECharts
                style={{ height: 260 }}
                option={{
                  tooltip: { trigger: 'axis' },
                  legend: { data: ['轮次', '平均时延(ms)'], bottom: 0, textStyle: { fontSize: 11 } },
                  grid: { left: 45, right: 45, top: 20, bottom: 40 },
                  xAxis: { type: 'category', data: data.daily.map((d) => d.date) },
                  yAxis: [
                    { type: 'value', name: '轮次' },
                    { type: 'value', name: 'ms' },
                  ],
                  series: [
                    { name: '轮次', type: 'bar', data: data.daily.map((d) => d.turns), itemStyle: { color: '#1677ff' } },
                    {
                      name: '平均时延(ms)',
                      type: 'line',
                      yAxisIndex: 1,
                      smooth: true,
                      data: data.daily.map((d) => d.avg_latency_ms),
                      itemStyle: { color: '#52c41a' },
                    },
                  ],
                }}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
            )}
          </Card>
        </Col>

        <Col span={10}>
          <Card
            size="small"
            title="工具调用 Top 10"
            extra={<a onClick={() => navigate('/mcp')}>管理工具</a>}
          >
            <Table
              size="small"
              rowKey="tool"
              pagination={false}
              dataSource={tools.filter((t) => t.call_count > 0).slice(0, 10)}
              locale={{ emptyText: '尚无调用记录' }}
              columns={[
                { title: '工具', dataIndex: 'tool', ellipsis: true, render: (v) => <span className="mono">{v}</span> },
                { title: '调用', dataIndex: 'call_count', width: 60 },
                {
                  title: '成功率',
                  dataIndex: 'success_rate',
                  width: 80,
                  render: (v: number | null) =>
                    v === null ? '-' : (
                      <Tag color={v >= 0.95 ? 'green' : v >= 0.8 ? 'orange' : 'red'}>
                        {(v * 100).toFixed(0)}%
                      </Tag>
                    ),
                },
                { title: '均耗时', dataIndex: 'avg_latency_ms', width: 80, render: (v: number) => `${v}ms` },
              ]}
            />
          </Card>
        </Col>
      </Row>

      {Object.entries(data.circuits ?? {}).some(([, c]) => c.state !== 'closed') && (
        <Alert
          style={{ marginTop: 12 }}
          type="warning"
          showIcon
          message="存在处于熔断状态的下游服务"
          description={Object.entries(data.circuits)
            .filter(([, c]) => c.state !== 'closed')
            .map(([name, c]) => `${name}：${c.state}（窗口失败率 ${(c.failure_rate * 100).toFixed(0)}%）`)
            .join('；')}
        />
      )}
    </>
  )
}

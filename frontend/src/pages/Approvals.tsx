import { useEffect, useState } from 'react'
import { App, Button, Card, Descriptions, Input, Modal, Segmented, Space, Table, Tag } from 'antd'
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

import { approvalApi, streamChat } from '@/api/client'
import { useAuth } from '@/store/auth'
import type { Approval } from '@/types'

const STATUS: Record<string, { color: string; text: string }> = {
  pending: { color: 'gold', text: '待审批' },
  approved: { color: 'green', text: '已批准' },
  rejected: { color: 'red', text: '已拒绝' },
  expired: { color: 'default', text: '已过期' },
}

export default function Approvals() {
  const { message, modal } = App.useApp()
  const { can } = useAuth()
  const [rows, setRows] = useState<Approval[]>([])
  const [status, setStatus] = useState<string>('pending')
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<Approval | null>(null)

  const load = () => {
    setLoading(true)
    approvalApi.list(status === 'all' ? {} : { status })
      .then(setRows)
      .finally(() => setLoading(false))
  }

  useEffect(load, [status])

  const decide = (row: Approval, decision: 'approved' | 'rejected') => {
    let comment = decision === 'approved' ? '已核实，同意执行' : '风险不可接受，拒绝执行'
    modal.confirm({
      title: decision === 'approved' ? '批准该高风险操作？' : '拒绝该操作？',
      width: 560,
      content: (
        <>
          <Descriptions size="small" column={1} bordered style={{ margin: '12px 0' }}>
            <Descriptions.Item label="工具"><span className="mono">{row.tool_name}</span></Descriptions.Item>
            <Descriptions.Item label="参数"><span className="mono">{JSON.stringify(row.tool_args)}</span></Descriptions.Item>
            <Descriptions.Item label="影响范围">{row.impact_scope}</Descriptions.Item>
          </Descriptions>
          <Input.TextArea rows={2} defaultValue={comment} onChange={(e) => { comment = e.target.value }}
                          placeholder="审批意见" />
        </>
      ),
      okText: decision === 'approved' ? '批准并恢复执行' : '拒绝',
      okButtonProps: { danger: decision === 'rejected' },
      onOk: async () => {
        await approvalApi.decide(row.id, decision, comment)
        message.success(decision === 'approved' ? '已批准，正在恢复执行…' : '已拒绝')
        if (decision === 'approved') {
          // 审批放行后由平台自动 resume 被中断的编排
          try {
            await streamChat({}, () => undefined, { path: `/api/chat/resume/${row.id}` })
            message.success('中断的任务已恢复执行完成')
          } catch (e: any) {
            message.warning(`恢复执行未完成：${e.message}`)
          }
        }
        load()
      },
    })
  }

  return (
    <>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <h2>人工审批（Human-in-the-Loop）</h2>
          <p>创建/修改/删除等高风险工具调用在执行前中断，展示工具名、参数、影响范围与风险原因，审批通过后恢复编排</p>
        </div>
        <Space>
          <Segmented
            value={status}
            onChange={(v) => setStatus(v as string)}
            options={[
              { label: '待审批', value: 'pending' },
              { label: '已批准', value: 'approved' },
              { label: '已拒绝', value: 'rejected' },
              { label: '全部', value: 'all' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        </Space>
      </div>

      <Card size="small">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          pagination={{ pageSize: 20 }}
          onRow={(row) => ({ onClick: () => setDetail(row) })}
          columns={[
            {
              title: '发起时间',
              dataIndex: 'created_at',
              width: 160,
              render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
            },
            {
              title: '执行体',
              width: 160,
              render: (_: any, row: Approval) => (
                <Space size={4}>
                  <Tag color={row.executor_type === 'agent' ? 'blue' : row.executor_type === 'skill' ? 'purple' : 'default'}>
                    {row.executor_type}
                  </Tag>
                  <span className="mono">{row.executor_name}</span>
                </Space>
              ),
            },
            { title: '目标工具', dataIndex: 'tool_name', render: (v) => <span className="mono">{v}</span> },
            {
              title: '参数',
              dataIndex: 'tool_args',
              ellipsis: true,
              render: (v: any) => <span className="mono">{JSON.stringify(v)}</span>,
            },
            { title: '影响范围', dataIndex: 'impact_scope', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 100,
              render: (v: string) => <Tag color={STATUS[v]?.color}>{STATUS[v]?.text ?? v}</Tag>,
            },
            { title: '审批人', dataIndex: 'decided_by', width: 100, render: (v) => v || '-' },
            {
              title: '操作',
              width: 160,
              render: (_: any, row: Approval) =>
                row.status === 'pending' && can('decide') ? (
                  <Space size={4} onClick={(e) => e.stopPropagation()}>
                    <Button size="small" type="primary" icon={<CheckOutlined />}
                            onClick={() => decide(row, 'approved')}>批准</Button>
                    <Button size="small" danger icon={<CloseOutlined />}
                            onClick={() => decide(row, 'rejected')}>拒绝</Button>
                  </Space>
                ) : null,
            },
          ]}
        />
      </Card>

      <Modal title="审批详情" open={!!detail} onCancel={() => setDetail(null)} footer={null} width={640}>
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="审批单号"><span className="mono">{detail.id}</span></Descriptions.Item>
            <Descriptions.Item label="会话"><span className="mono">{detail.session_id}</span></Descriptions.Item>
            <Descriptions.Item label="Trace"><span className="mono">{detail.trace_id || '-'}</span></Descriptions.Item>
            <Descriptions.Item label="执行体">{detail.executor_type} · {detail.executor_name}</Descriptions.Item>
            <Descriptions.Item label="工具"><span className="mono">{detail.tool_name}</span></Descriptions.Item>
            <Descriptions.Item label="参数">
              <div className="code-block">{JSON.stringify(detail.tool_args, null, 2)}</div>
            </Descriptions.Item>
            <Descriptions.Item label="风险原因">{detail.risk_reason}</Descriptions.Item>
            <Descriptions.Item label="影响范围">{detail.impact_scope}</Descriptions.Item>
            <Descriptions.Item label="状态"><Tag color={STATUS[detail.status]?.color}>{STATUS[detail.status]?.text}</Tag></Descriptions.Item>
            <Descriptions.Item label="审批意见">{detail.decision_comment || '-'}</Descriptions.Item>
            <Descriptions.Item label="审批时间">
              {detail.decided_at ? dayjs(detail.decided_at).format('YYYY-MM-DD HH:mm:ss') : '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </>
  )
}

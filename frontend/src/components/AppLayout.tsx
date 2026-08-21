import { useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Badge, Dropdown, Layout, Menu, Tag, Tooltip } from 'antd'
import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  LogoutOutlined,
  MessageOutlined,
  NodeIndexOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons'

import { approvalApi, http } from '@/api/client'
import { useAuth } from '@/store/auth'

const { Sider, Header, Content } = Layout

const MENU = [
  { key: '/', icon: <DashboardOutlined />, label: '总览看板' },
  { key: '/playground', icon: <MessageOutlined />, label: '对话调试台' },
  { key: '/mcp', icon: <ApartmentOutlined />, label: 'MCP 注册中心' },
  { key: '/skills', icon: <ThunderboltOutlined />, label: 'Skill 注册中心' },
  { key: '/agents', icon: <NodeIndexOutlined />, label: '子 Agent 注册中心' },
  { key: '/approvals', icon: <SafetyCertificateOutlined />, label: '人工审批' },
  { key: '/traces', icon: <AuditOutlined />, label: '全链路观测' },
  { key: '/evaluation', icon: <ExperimentOutlined />, label: '评测平台' },
]

const ROLE_LABEL: Record<string, string> = {
  admin: '管理员',
  developer: '开发者',
  viewer: '只读',
}

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuth()
  const [pending, setPending] = useState(0)
  const [mode, setMode] = useState<{ mode: string; primary_model: string } | null>(null)

  useEffect(() => {
    http.get('/health').then((r) => setMode(r.data)).catch(() => undefined)
  }, [])

  useEffect(() => {
    const load = () =>
      approvalApi.list({ status: 'pending' }).then((rows) => setPending(rows.length)).catch(() => undefined)
    load()
    const timer = setInterval(load, 15000)
    return () => clearInterval(timer)
  }, [location.pathname])

  const items = MENU.map((item) =>
    item.key === '/approvals'
      ? { ...item, label: <Badge count={pending} offset={[10, 0]} size="small">{item.label}</Badge> }
      : item,
  )

  const current = MENU.find((m) => m.key !== '/' && location.pathname.startsWith(m.key))?.key ?? '/'

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark">
        <div className="app-logo">
          <span className="dot" />
          <span>RSI Agent Platform</span>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[current]}
          items={items}
          onClick={({ key }) => navigate(key)}
          style={{ borderInlineEnd: 'none' }}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 24px',
            borderBottom: '1px solid var(--border-soft)',
          }}
        >
          <div style={{ fontWeight: 600, fontSize: 15 }}>
            {MENU.find((m) => m.key === current)?.label ?? '总览看板'}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            {mode && (
              <Tooltip
                title={
                  mode.mode === 'llm'
                    ? `主模型 ${mode.primary_model}，失败时自动切换备用模型`
                    : '未配置 ANTHROPIC_API_KEY，Supervisor 已降级为规则引擎路由，编排/审批/观测链路完整可用'
                }
              >
                <Tag color={mode.mode === 'llm' ? 'green' : 'orange'} style={{ margin: 0 }}>
                  {mode.mode === 'llm' ? `模型：${mode.primary_model}` : '规则引擎降级模式'}
                </Tag>
              </Tooltip>
            )}
            <Dropdown
              menu={{
                items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }],
              }}
            >
              <span style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
                <UserOutlined />
                {user?.display_name || user?.username}
                <Tag color="blue" style={{ margin: 0 }}>{ROLE_LABEL[user?.role ?? ''] ?? user?.role}</Tag>
              </span>
            </Dropdown>
          </div>
        </Header>

        <Content style={{ padding: 20, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}

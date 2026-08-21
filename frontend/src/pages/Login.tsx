import { useState } from 'react'
import { Alert, Button, Card, Form, Input, Typography } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useAuth } from '@/store/auth'

export default function Login() {
  const { login } = useAuth()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true)
    setError('')
    try {
      await login(values.username, values.password)
    } catch (e: any) {
      setError(e.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        height: '100vh',
        display: 'grid',
        placeItems: 'center',
        background: 'linear-gradient(135deg, #141926 0%, #223052 60%, #1677ff 160%)',
      }}
    >
      <Card style={{ width: 400, boxShadow: '0 12px 40px rgba(0,0,0,.25)' }} bordered={false}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            RSI Agent Platform
          </Typography.Title>
          <Typography.Text type="secondary">企业级多 Agent 运行中台</Typography.Text>
        </div>

        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}

        <Form onFinish={onFinish} initialValues={{ username: 'admin', password: 'admin123' }} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登录
          </Button>
        </Form>

        <Typography.Paragraph type="secondary" style={{ marginTop: 20, marginBottom: 0, fontSize: 12 }}>
          演示账号：<code>admin / admin123</code>（管理员）· <code>dev / dev123</code>（开发者）·{' '}
          <code>viewer / viewer123</code>（只读）
        </Typography.Paragraph>
      </Card>
    </div>
  )
}

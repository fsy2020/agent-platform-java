import { Navigate, Route, Routes } from 'react-router-dom'
import { Spin } from 'antd'

import AppLayout from '@/components/AppLayout'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import McpRegistry from '@/pages/McpRegistry'
import Skills from '@/pages/Skills'
import Agents from '@/pages/Agents'
import Playground from '@/pages/Playground'
import Approvals from '@/pages/Approvals'
import Traces from '@/pages/Traces'
import Evaluation from '@/pages/Evaluation'
import { useAuth } from '@/store/auth'

export default function App() {
  const { user, loading } = useAuth()

  if (loading) {
    return (
      <div style={{ height: '100vh', display: 'grid', placeItems: 'center' }}>
        <Spin size="large" tip="加载中…" />
      </div>
    )
  }

  if (!user) {
    return (
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }

  return (
    <Routes>
      <Route path="/login" element={<Navigate to="/" replace />} />
      <Route element={<AppLayout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/mcp" element={<McpRegistry />} />
        <Route path="/skills" element={<Skills />} />
        <Route path="/agents" element={<Agents />} />
        <Route path="/playground" element={<Playground />} />
        <Route path="/approvals" element={<Approvals />} />
        <Route path="/traces" element={<Traces />} />
        <Route path="/evaluation" element={<Evaluation />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

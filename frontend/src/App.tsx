import { useEffect, useState } from 'react'
import { NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { clearToken, get, type Dashboard } from './api'
import { LoginPage, useAuth, type AuthUser } from './auth'
import DashboardPage from './pages/DashboardPage'
import CasesPage from './pages/CasesPage'
import CaseDetailPage from './pages/CaseDetailPage'
import ApprovalsPage from './pages/ApprovalsPage'
import InvoicesPage from './pages/InvoicesPage'
import AutonomyPage from './pages/AutonomyPage'
import DemoPage from './pages/DemoPage'
import PortalPage from './pages/PortalPage'

export default function App() {
  const location = useLocation()
  if (location.pathname.startsWith('/pay/')) {
    return (
      <Routes>
        <Route path="/pay/:token" element={<PortalPage />} />
      </Routes>
    )
  }
  return <AuthGate />
}

function AuthGate() {
  const { config, user, setUser, checking, failed } = useAuth()
  if (checking) return <div className="login-wrap muted">Loading…</div>
  if (failed || !config) return <div className="login-wrap muted">Backend not reachable</div>
  if (config.enabled && !user) return <LoginPage clientId={config.googleClientId} onLogin={setUser} />
  return <Shell user={config.enabled ? user : null} />
}

function Shell({ user }: { user: AuthUser | null }) {
  const location = useLocation()
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          CashFlow <span>AI</span>
        </div>
        <nav>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/cases">Cases</NavLink>
          <NavLink to="/approvals">Approvals</NavLink>
          <NavLink to="/invoices">Invoices</NavLink>
          <NavLink to="/autonomy">Autonomy</NavLink>
          <NavLink to="/demo">Demo controls</NavLink>
        </nav>
        <StatusFooter key={location.pathname} />
        {user && (
          <div className="sidebar-footer">
            <div>{user.email}</div>
            <button onClick={() => { clearToken(); window.location.assign('/') }}>Sign out</button>
          </div>
        )}
      </aside>
      <main className="content">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/cases" element={<CasesPage />} />
          <Route path="/cases/:id" element={<CaseDetailPage />} />
          <Route path="/approvals" element={<ApprovalsPage />} />
          <Route path="/invoices" element={<InvoicesPage />} />
          <Route path="/autonomy" element={<AutonomyPage />} />
          <Route path="/demo" element={<DemoPage />} />
        </Routes>
      </main>
    </div>
  )
}

function StatusFooter() {
  const [d, setD] = useState<Dashboard | null>(null)
  useEffect(() => {
    get<Dashboard>('/api/dashboard').then(setD).catch(() => setD(null))
  }, [])
  if (!d) return <div className="sidebar-footer muted">Backend not reachable</div>
  return (
    <div className="sidebar-footer">
      <div>Business date: <b>{d.today}</b></div>
      <div>Agent: <b>{d.agentBrain}</b></div>
      <div>PayPal: <b>{d.paypalEnabled ? 'sandbox' : 'simulated'}</b></div>
    </div>
  )
}

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
import TimelinePage from './pages/TimelinePage'
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
          <div className="nav-label">Workspace</div>
          <NavLink to="/" end><Icon d={ICONS.dashboard} />Dashboard</NavLink>
          <NavLink to="/cases"><Icon d={ICONS.cases} />Cases</NavLink>
          <NavLink to="/approvals"><Icon d={ICONS.approvals} />Approvals</NavLink>
          <NavLink to="/invoices"><Icon d={ICONS.invoices} />Invoices</NavLink>
          <NavLink to="/timeline"><Icon d={ICONS.timeline} />Timeline</NavLink>
          <div className="nav-label">Agent</div>
          <NavLink to="/autonomy"><Icon d={ICONS.autonomy} />Autonomy</NavLink>
          <NavLink to="/demo"><Icon d={ICONS.demo} />Demo controls</NavLink>
        </nav>
        <StatusFooter key={location.pathname} />
        {user && (
          <div className="sidebar-footer user">
            <div className="user-row">
              <span className="avatar">{user.email.charAt(0).toUpperCase()}</span>
              <span className="user-email" title={user.email}>{user.email}</span>
            </div>
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
          <Route path="/timeline" element={<TimelinePage />} />
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
      <div className="status-row"><span>Business date</span><b>{d.today}</b></div>
      <div className="status-row"><span>Agent</span><b>{d.agentBrain}</b></div>
      <div className="status-row"><span>PayPal</span><b>{d.paypalEnabled ? 'sandbox' : 'simulated'}</b></div>
    </div>
  )
}

const ICONS = {
  dashboard: 'M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z',
  cases: 'M4 7h16M4 12h16M4 17h10',
  approvals: 'M5 12l4.5 4.5L19 7',
  invoices: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h7',
  timeline: 'M4 6h16v14H4zM4 10h16M8 3v4M16 3v4',
  autonomy: 'M4 8h10M18 8h2M4 16h2M10 16h10M14 6v4M6 14v4',
  demo: 'M8 5l11 7-11 7z',
}

function Icon({ d }: { d: string }) {
  return (
    <svg className="nav-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
         strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}

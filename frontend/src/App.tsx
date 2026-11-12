import { useEffect, useState } from 'react'
import { NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { get, type Dashboard } from './api'
import { DevLoginPage, LoginPage, RolePicker, signOut, switchRole, useAuth, type AuthUser } from './auth'
import DashboardPage from './pages/DashboardPage'
import CasesPage from './pages/CasesPage'
import CaseDetailPage from './pages/CaseDetailPage'
import ApprovalsPage from './pages/ApprovalsPage'
import InvoicesPage from './pages/InvoicesPage'
import AutonomyPage from './pages/AutonomyPage'
import TimelinePage from './pages/TimelinePage'
import DemoPage from './pages/DemoPage'
import PortalPage from './pages/PortalPage'
import UsersPage from './pages/UsersPage'
import CustomerPage from './pages/CustomerPage'

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

/** Sign in (Google, or local email) -> pick customer or merchant -> (merchant) pick organization. */
function AuthGate() {
  const { config, user, setUser, checking, failed } = useAuth()
  if (checking) return <div className="login-wrap muted">Loading…</div>
  if (failed || !config) return <div className="login-wrap muted">Backend not reachable</div>
  if (!user) {
    return config.enabled
      ? <LoginPage clientId={config.googleClientId} onLogin={setUser} />
      : <DevLoginPage onLogin={setUser} />
  }
  if (user.role === 'CUSTOMER') return <CustomerShell user={user} />
  if (user.role === 'MERCHANT') return <Shell user={user} />
  return <RolePicker user={user} />
}

function AccountFooter({ user }: { user: AuthUser }) {
  return (
    <div className="sidebar-footer user">
      <div className="user-row">
        <span className="avatar">{user.email.charAt(0).toUpperCase()}</span>
        <span className="user-email" title={user.email}>{user.email}</span>
      </div>
      <div className="status-row"><span>As</span><b>{user.role === 'CUSTOMER' ? 'Customer' : user.tenantName}</b></div>
      <div className="row">
        <button onClick={switchRole}>Switch</button>
        <button onClick={signOut}>Sign out</button>
      </div>
    </div>
  )
}

function CustomerShell({ user }: { user: AuthUser }) {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          CashFlow <span>AI</span>
        </div>
        <div className="tenant-name">Customer</div>
        <nav>
          <div className="nav-label">Your account</div>
          <NavLink to="/" end><Icon d={ICONS.invoices} />Invoices</NavLink>
          <NavLink to="/timeline"><Icon d={ICONS.timeline} />Timeline</NavLink>
        </nav>
        <AccountFooter user={user} />
      </aside>
      <main className="content">
        <Routes>
          <Route path="/" element={<CustomerPage user={user} />} />
          <Route path="/timeline" element={<TimelinePage source="/api/customer/timeline" readOnly />} />
          <Route path="*" element={<CustomerPage user={user} />} />
        </Routes>
      </main>
    </div>
  )
}

function Shell({ user }: { user: AuthUser }) {
  const location = useLocation()
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          CashFlow <span>AI</span>
        </div>
        {user.tenantName && <div className="tenant-name">{user.tenantName}</div>}
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
          <div className="nav-label">Organization</div>
          <NavLink to="/users"><Icon d={ICONS.members} />Members</NavLink>
        </nav>
        <StatusFooter key={location.pathname} />
        <AccountFooter user={user} />
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
          <Route path="/users" element={<UsersPage />} />
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
  members: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M16 3.5a4 4 0 0 1 0 7M18 21a7 7 0 0 0-3-5.7',
}

function Icon({ d }: { d: string }) {
  return (
    <svg className="nav-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
         strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}

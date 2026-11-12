import { useEffect, useRef, useState } from 'react'
import { get, post, setToken } from './api'

export interface AuthUser {
  email: string
  name: string
  picture: string
}

interface AuthConfig {
  enabled: boolean
  googleClientId: string
}

declare global {
  interface Window {
    google?: any
  }
}

function loadGoogleScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) return resolve()
    const s = document.createElement('script')
    s.src = 'https://accounts.google.com/gsi/client'
    s.async = true
    s.onload = () => resolve()
    s.onerror = () => reject(new Error('Could not load Google sign-in'))
    document.head.appendChild(s)
  })
}

export function LoginPage({ clientId, onLogin }: { clientId: string; onLogin: (u: AuthUser) => void }) {
  const btn = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    loadGoogleScript()
      .then(() => {
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: async (resp: { credential: string }) => {
            try {
              const r = await post<{ token: string; user: AuthUser }>('/api/auth/google', {
                credential: resp.credential,
              })
              setToken(r.token)
              onLogin(r.user)
            } catch (e) {
              setError((e as Error).message)
            }
          },
        })
        window.google.accounts.id.renderButton(btn.current, { theme: 'outline', size: 'large', text: 'signin_with' })
      })
      .catch((e) => setError(e.message))
  }, [clientId, onLogin])

  return (
    <div className="login-page">
      <section className="login-hero">
        <div className="login-top">
          <Mark />
          <span className="login-wordmark">CashFlow&nbsp;AI</span>
        </div>

        <div className="login-copy">
          <p className="login-kicker">Accounts receivable, on autopilot</p>
          <h1>Late invoices get followed up before you notice them.</h1>
          <p className="login-lead">
            CashFlow AI watches your PayPal invoices, matches payments as they land, chases the ones that slip,
            and brings you in only when a person should decide.
          </p>
        </div>

        <div className="ledger" aria-label="Example agent activity">
          <div className="ledger-head">
            <span><i className="live" /> Agent log</span>
            <span>sample data</span>
          </div>
          {LEDGER.map((r, i) => (
            <div className="ledger-row" style={{ animationDelay: `${0.25 + i * 0.35}s` }} key={r.inv}>
              <time>{r.time}</time>
              <span className="inv">{r.inv}</span>
              <span className="what">{r.what}</span>
              <span className={`amt ${r.tone}`}>{r.amt}</span>
            </div>
          ))}
        </div>

        <ol className="login-steps">
          <li><b>01</b> Reconcile<small>Payments matched to invoices automatically.</small></li>
          <li><b>02</b> Follow up<small>Reminders with a Fix &amp; Pay link.</small></li>
          <li><b>03</b> Escalate<small>You approve anything outside your rules.</small></li>
        </ol>
      </section>

      <section className="login-panel">
        <div className="login-form">
          <p className="login-kicker dark">Sign in</p>
          <h2>Pick up where the agent left off.</h2>
          <p className="login-sub">Use the Google account your team works with.</p>
          <div ref={btn} className="login-btn" />
          {error && <p className="login-error">{error}</p>}
          <hr />
          <p className="login-fine">
            We read your name, email and profile picture to identify you. We never post, send or read anything
            in your Google account.
          </p>
        </div>
        <footer className="login-foot">Invoices and payments via PayPal</footer>
      </section>
    </div>
  )
}

const LEDGER = [
  { time: '09:02', inv: 'INV-1042', what: 'Reminder sent', amt: '$4,200.00', tone: 'out' },
  { time: '09:14', inv: 'INV-1038', what: 'Payment matched', amt: '$1,850.00', tone: 'in' },
  { time: '09:31', inv: 'INV-1029', what: 'Plan proposed, 2 parts', amt: '$6,400.00', tone: 'out' },
  { time: '09:47', inv: 'INV-1017', what: 'Waiting for your approval', amt: '$12,000.00', tone: 'hold' },
]

export function Mark({ size = 30 }: { size?: number }) {
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="#f2c94c" />
      <path d="M9 11h14M9 16h14M9 21h8" stroke="#0b1b3a" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M20 19.5l3 2.5 3-2.5" fill="none" stroke="#0b1b3a" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** Resolves login state: 'loading' while checking, null when sign-in is needed. */
export function useAuth() {
  const [config, setConfig] = useState<AuthConfig | null>(null)
  const [user, setUser] = useState<AuthUser | null>(null)
  const [checking, setChecking] = useState(true)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    get<AuthConfig>('/api/auth/config')
      .then(async (c) => {
        setConfig(c)
        if (c.enabled) {
          try {
            setUser(await get<AuthUser>('/api/auth/me'))
          } catch {
            setUser(null)
          }
        }
      })
      .catch(() => setFailed(true))
      .finally(() => setChecking(false))
  }, [])

  return { config, user, setUser, checking, failed }
}

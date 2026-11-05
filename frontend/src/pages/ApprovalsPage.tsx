import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { get, money, post, when, type PendingAction } from '../api'
import { Badge } from '../components/Badge'

export default function ApprovalsPage() {
  const [items, setItems] = useState<PendingAction[]>([])
  const [error, setError] = useState('')
  const [busy, setBusy] = useState<number | null>(null)

  const load = useCallback(() => {
    get<PendingAction[]>('/api/actions/pending').then(setItems).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  const decide = async (id: number, decision: 'approve' | 'reject') => {
    setBusy(id)
    setError('')
    try {
      await post(`/api/actions/${id}/${decision}?by=owner`)
      load()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Waiting for your approval</h1>
          <p className="muted">The agent proposed these actions, but your policy says a person must decide.</p>
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}
      {items.length === 0 && <section className="card muted">Nothing waiting. The agent is working inside your rules.</section>}
      {items.map(({ action, caseInfo }) => (
        <section className="card approval" key={action.id}>
          <div className="row spread">
            <div>
              <h3><Link to={`/cases/${caseInfo.id}`}>{caseInfo.invoiceNumber}</Link> · {caseInfo.customer} · {money(caseInfo.amount)}</h3>
              <div className="row"><Badge value={caseInfo.rootCause} /> <span className="muted small">{when(action.createdAt)}</span></div>
            </div>
            <div className="row">
              <button className="primary" disabled={busy === action.id} onClick={() => decide(action.id, 'approve')}>Approve</button>
              <button className="danger" disabled={busy === action.id} onClick={() => decide(action.id, 'reject')}>Reject</button>
            </div>
          </div>
          <p><b>Proposed:</b> <code>{action.toolName}</code> {describe(action.toolInput)}</p>
          <p className="muted">Why it needs you: {action.policyReason}</p>
          {caseInfo.explanation && <p className="muted">Agent's reasoning: {caseInfo.explanation}</p>}
        </section>
      ))}
    </div>
  )
}

function describe(json: string | null) {
  if (!json) return ''
  try {
    const o = JSON.parse(json) as Record<string, unknown>
    return Object.entries(o).map(([k, v]) => `${k.replace(/_/g, ' ')}: ${v}`).join(', ')
  } catch {
    return json
  }
}

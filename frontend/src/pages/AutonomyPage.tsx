import { useCallback, useEffect, useState } from 'react'
import { get, label, post, type AutonomyRule } from '../api'
import { Badge } from '../components/Badge'

const EXPLAIN: Record<string, string> = {
  UPDATE_REFERENCE: 'Add or correct a PO number on an invoice and re-send it through PayPal. Amounts never change.',
  SEND_REMINDER: 'Send reminders on invoices more than 60 days late (earlier reminders are always automatic).',
  RECONCILE_PAYMENT: 'Apply a payment that is smaller than the balance (partial payment). Exact matches are always automatic; overpayments always need you.',
}

export default function AutonomyPage() {
  const [rules, setRules] = useState<AutonomyRule[]>([])
  const [error, setError] = useState('')

  const load = useCallback(() => {
    get<AutonomyRule[]>('/api/autonomy').then(setRules).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  const setMode = async (type: string, mode: 'AUTO' | 'APPROVAL') => {
    try {
      await post(`/api/autonomy/${type}?mode=${mode}`)
      load()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Earned autonomy</h1>
          <p className="muted">
            The agent starts by asking you. Each approval builds a streak; one rejection resets it and takes autonomy away.
            Refunds, write-offs, amount changes and discounts can never be handed over.
          </p>
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}
      {rules.map((r) => (
        <section className={`card rule ${r.mode === 'AUTO' ? 'auto' : 'ask'}`} key={r.actionType}>
          <div>
            <h3>{label(r.actionType)} <Badge value={r.mode} /></h3>
            <p className="muted">{EXPLAIN[r.actionType]}</p>
            <div className="streak">
              Approval streak <b>{r.approvalStreak}</b>
              {r.suggestPromotion && <span className="badge green">Ready to promote</span>}
            </div>
          </div>
          {r.mode === 'APPROVAL'
            ? <button className="primary" onClick={() => setMode(r.actionType, 'AUTO')}>Let the agent do this</button>
            : <button onClick={() => setMode(r.actionType, 'APPROVAL')}>Require my approval again</button>}
        </section>
      ))}
    </div>
  )
}

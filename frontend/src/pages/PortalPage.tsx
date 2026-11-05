import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { get, label, money, post, when, type PortalView, type ResolutionResult } from '../api'

type Tab = 'po' | 'paid' | 'plan' | 'promise' | 'dispute' | 'message'

const TABS: { id: Tab; title: string }[] = [
  { id: 'po', title: 'Add a PO number' },
  { id: 'paid', title: 'We already paid' },
  { id: 'plan', title: 'Pay in parts' },
  { id: 'promise', title: 'Pay on a date' },
  { id: 'dispute', title: 'Something is wrong' },
  { id: 'message', title: 'Send a message' },
]

type PlanRow = { amount: string; due: string }

export default function PortalPage() {
  const { token } = useParams()
  const base = `/api/portal/${token}`
  const [inv, setInv] = useState<PortalView | null>(null)
  const [tab, setTab] = useState<Tab>('po')
  const [result, setResult] = useState<ResolutionResult | null>(null)
  const [error, setError] = useState('')
  const [form, setForm] = useState({ po: '', reference: '', installments: 2, days: 30, amount: '', payBy: '', text: '' })
  const [quote, setQuote] = useState<ResolutionResult | null>(null)

  const load = useCallback(() => {
    get<PortalView>(base).then(setInv).catch((e) => setError(e.message))
  }, [base])
  useEffect(load, [load])

  const send = async (path: string, body: unknown) => {
    setError('')
    setResult(null)
    try {
      const r = await post<ResolutionResult>(base + path, body)
      setResult(r)
      load()
      return r
    } catch (e) {
      setError((e as Error).message)
    }
  }

  if (!inv) return <div className="portal"><p>{error || 'Loading…'}</p></div>
  const closed = inv.status === 'PAID' || inv.status === 'CANCELLED' || inv.status === 'DISPUTED'
  const plan = (quote?.data?.plan ?? []) as PlanRow[]

  return (
    <div className="portal">
      <div className="portal-card">
        <div className="muted small">Invoice from your supplier</div>
        <h1>{inv.invoice_number}</h1>
        <div className="portal-amount">{money(inv.outstanding, inv.currency)} <span className="muted small">outstanding of {money(inv.amount, inv.currency)}</span></div>
        <div className="row">
          <span className={`badge ${inv.status === 'PAID' ? 'green' : inv.days_overdue > 0 ? 'amber' : 'blue'}`}>{label(inv.status)}</span>
          <span className="muted">Due {inv.due_date}{inv.days_overdue > 0 ? ` · ${inv.days_overdue} days late` : ''}</span>
          {inv.purchase_order && <span className="muted">PO {inv.purchase_order}</span>}
        </div>
        {inv.pay_url && !closed && (
          <a className="paypal-button" href={inv.pay_url} target="_blank" rel="noreferrer">Pay with PayPal</a>
        )}
        {!inv.pay_url && !closed && <p className="muted small">Online payment opens once this invoice is published to PayPal.</p>}
      </div>

      {result && <div className={`alert ${result.ok ? 'success' : 'error'}`}>{result.message}</div>}
      {error && <div className="alert error">{error}</div>}

      {!closed && (
        <div className="portal-card">
          <h2>Something stopping you from paying?</h2>
          <div className="chips">
            {TABS.map((t) => (
              <button key={t.id} className={`chip ${tab === t.id ? 'active' : ''}`} onClick={() => { setTab(t.id); setResult(null) }}>
                {t.title}
              </button>
            ))}
          </div>

          {tab === 'po' && (
            <div className="form">
              <label>Your purchase order number<input value={form.po} placeholder="PO-88421" onChange={(e) => setForm({ ...form, po: e.target.value })} /></label>
              <button className="primary" onClick={() => send('/po', { purchaseOrder: form.po })}>Add PO and re-send invoice</button>
            </div>
          )}
          {tab === 'paid' && (
            <div className="form">
              <label>PayPal transaction id or payment reference<input value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></label>
              <button className="primary" onClick={() => send('/already-paid', { reference: form.reference })}>Check my payment</button>
            </div>
          )}
          {tab === 'plan' && (
            <div className="form">
              <p className="muted small">
                Your supplier allows up to {inv.plan_limits.max_installments} payments within {inv.plan_limits.max_days} days,
                first payment at least {Math.round(inv.plan_limits.min_first_share * 100)}%.
              </p>
              <label>Number of payments<input type="number" min={1} value={form.installments} onChange={(e) => setForm({ ...form, installments: Number(e.target.value) })} /></label>
              <label>Spread over (days)<input type="number" min={0} value={form.days} onChange={(e) => setForm({ ...form, days: Number(e.target.value) })} /></label>
              <button onClick={async () => setQuote((await send('/plan/quote', { installments: form.installments, days: form.days })) ?? null)}>See offer</button>
              {plan.length > 0 && (
                <div className="offer">
                  <ul className="plain">{plan.map((p, i) => <li key={i}>{money(p.amount)} by {p.due}</li>)}</ul>
                  <button className="primary" onClick={() => send('/plan/accept', { installments: form.installments, days: form.days })}>Accept this plan</button>
                </div>
              )}
            </div>
          )}
          {tab === 'promise' && (
            <div className="form">
              <label>Amount<input value={form.amount} placeholder={inv.outstanding} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></label>
              <label>We will pay by<input type="date" value={form.payBy} onChange={(e) => setForm({ ...form, payBy: e.target.value })} /></label>
              <button className="primary" onClick={() => send('/promise', { amount: form.amount || inv.outstanding, payBy: form.payBy })}>Confirm date</button>
            </div>
          )}
          {tab === 'dispute' && (
            <div className="form">
              <label>What is wrong with this invoice?<textarea rows={3} value={form.text} onChange={(e) => setForm({ ...form, text: e.target.value })} /></label>
              <button className="danger" onClick={() => send('/dispute', { text: form.text })}>Raise a dispute</button>
            </div>
          )}
          {tab === 'message' && (
            <div className="form">
              <label>Message<textarea rows={3} value={form.text} onChange={(e) => setForm({ ...form, text: e.target.value })} /></label>
              <button className="primary" onClick={() => send('/message', { text: form.text })}>Send</button>
            </div>
          )}
        </div>
      )}

      {(inv.messages.length > 0 || inv.promises.length > 0) && (
        <div className="portal-card">
          {inv.promises.length > 0 && (
            <>
              <h2>Agreed payments</h2>
              <ul className="plain">{inv.promises.map((p, i) => <li key={i}>{money(p.amount)} by {p.due} · {label(p.status)}</li>)}</ul>
            </>
          )}
          {inv.messages.length > 0 && (
            <>
              <h2>History</h2>
              {inv.messages.map((m, i) => (
                <div key={i} className={`bubble from-${m.from.toLowerCase()}`}>
                  <div className="bubble-meta">{label(m.from)} · {when(m.at)}</div>{m.text}
                </div>
              ))}
            </>
          )}
        </div>
      )}
      <p className="muted small center">Secured link for this invoice only. Payments are processed by PayPal.</p>
    </div>
  )
}

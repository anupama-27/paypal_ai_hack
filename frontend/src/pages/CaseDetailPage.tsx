import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { get, label, money, post, when, type AgentAction, type CaseDetail } from '../api'
import { Actor, Badge } from '../components/Badge'

export default function CaseDetailPage() {
  const { id } = useParams()
  const [detail, setDetail] = useState<CaseDetail | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    get<CaseDetail>(`/api/cases/${id}`).then(setDetail).catch((e) => setError(e.message))
  }, [id])

  useEffect(() => {
    load()
    // Live view: a PayPal webhook or a customer action can change the case while you watch.
    const t = window.setInterval(load, 3000)
    return () => window.clearInterval(t)
  }, [load])

  const act = async (path: string) => {
    setBusy(true)
    setError('')
    try {
      await post(path)
      load()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (!detail) return <div className="muted">{error || 'Loading…'}</div>
  const { caseInfo: c, invoice: inv, actions } = detail

  return (
    <div>
      <header className="page-header">
        <div>
          <div className="muted"><Link to="/cases">Cases</Link> / #{c.id}</div>
          <h1>{c.invoiceNumber} · {c.customer}</h1>
          <div className="row">
            <Badge value={c.caseType} /> <Badge value={c.status} /> <Badge value={inv.status} />
          </div>
        </div>
        <div className="row">
          {c.status === 'DETECTED' && (
            <button onClick={() => act(`/api/cases/${c.id}/investigate`)} disabled={busy}>Investigate now</button>
          )}
          {c.status === 'ESCALATED' && (
            <button onClick={() => act(`/api/cases/${c.id}/resolve`)} disabled={busy}>Mark resolved</button>
          )}
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}

      <div className="grid-2">
        <section className="card">
          <h2>Diagnosis</h2>
          {c.rootCause ? (
            <>
              <div className="row"><Badge value={c.rootCause} />
                {c.confidence != null && <Confidence value={c.confidence} />}
              </div>
              <p>{c.explanation}</p>
              {c.recommendedAction && <p className="muted">Next step: {c.recommendedAction}</p>}
            </>
          ) : (
            <p className="muted">{c.explanation ?? 'Not investigated yet.'}</p>
          )}
          {c.nextCheckAt && <p className="muted">Follow-up check on {c.nextCheckAt} if no payment arrives.</p>}
        </section>

        <section className="card">
          <h2>Invoice</h2>
          <dl className="facts">
            <dt>Amount</dt><dd>{money(inv.amount, inv.currency)}</dd>
            <dt>Paid</dt><dd>{money(inv.paid, inv.currency)}</dd>
            <dt>Outstanding</dt><dd><b>{money(inv.outstanding, inv.currency)}</b></dd>
            <dt>Due</dt><dd>{inv.due_date} {inv.days_overdue > 0 && <span className="muted">({inv.days_overdue} days late)</span>}</dd>
            <dt>PO</dt><dd>{inv.purchase_order ?? <span className="muted">none</span>}</dd>
            <dt>Customer link</dt><dd><a href={detail.fixAndPayLink} target="_blank" rel="noreferrer">Fix &amp; Pay page</a></dd>
            {inv.pay_url && (<><dt>PayPal</dt><dd><a href={inv.pay_url} target="_blank" rel="noreferrer">Invoice on PayPal</a></dd></>)}
          </dl>
          {inv.promises.length > 0 && (
            <>
              <h3>Promises and plan installments</h3>
              <ul className="plain">
                {inv.promises.map((p, i) => (
                  <li key={i}>{money(p.amount)} by {p.due} <Badge value={p.status} /> <span className="muted">{label(p.source)}</span></li>
                ))}
              </ul>
            </>
          )}
        </section>
      </div>

      <div className="grid-2">
        <section className="card">
          <h2>Audit trail</h2>
          <ol className="timeline">
            {actions.map((a) => (
              <TimelineItem key={a.id} action={a} busy={busy}
                            onApprove={() => act(`/api/actions/${a.id}/approve?by=owner`)}
                            onReject={() => act(`/api/actions/${a.id}/reject?by=owner`)} />
            ))}
            {actions.length === 0 && <li className="muted">Nothing yet.</li>}
          </ol>
        </section>

        <section className="card">
          <h2>Conversation</h2>
          {inv.messages.length === 0 && <p className="muted">No messages from the customer.</p>}
          {inv.messages.map((m, i) => (
            <div key={i} className={`bubble from-${m.from.toLowerCase()}`}>
              <div className="bubble-meta">{label(m.from)} · {when(m.at)}</div>
              {m.text}
            </div>
          ))}
        </section>
      </div>
    </div>
  )
}

function Confidence({ value }: { value: number }) {
  return (
    <span className="confidence" title="Agent confidence">
      <span style={{ width: `${Math.round(value * 100)}%` }} />
      <em>{Math.round(value * 100)}%</em>
    </span>
  )
}

function TimelineItem(props: { action: AgentAction; busy: boolean; onApprove: () => void; onReject: () => void }) {
  const a = props.action
  const [open, setOpen] = useState(false)
  return (
    <li className={`tl tl-${a.status.toLowerCase()}`}>
      <div className="tl-head">
        <Actor value={a.actor} />
        <code>{a.toolName}</code>
        <Badge value={a.status} />
        <span className="muted small">{when(a.executedAt ?? a.createdAt)}</span>
      </div>
      {a.policyReason && <div className="tl-reason">Policy: {a.policyReason}</div>}
      {a.approvedBy && <div className="tl-reason">Decided by {a.approvedBy}</div>}
      {a.status === 'PENDING_APPROVAL' && (
        <div className="row">
          <button className="primary" disabled={props.busy} onClick={props.onApprove}>Approve</button>
          <button className="danger" disabled={props.busy} onClick={props.onReject}>Reject</button>
        </div>
      )}
      <button className="link" onClick={() => setOpen(!open)}>{open ? 'Hide details' : 'Details'}</button>
      {open && (
        <pre className="json">{pretty(a.toolInput)}{'\n→ '}{pretty(a.toolOutput)}</pre>
      )}
    </li>
  )
}

function pretty(json: string | null) {
  if (!json) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

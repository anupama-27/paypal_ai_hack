import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { get, money, post, type AutonomyRule, type CaseView, type Dashboard, type RunView } from '../api'
import { Badge } from '../components/Badge'

export default function DashboardPage() {
  const [d, setD] = useState<Dashboard | null>(null)
  const [cases, setCases] = useState<CaseView[]>([])
  const [run, setRun] = useState<RunView | null>(null)
  const [suggestions, setSuggestions] = useState<AutonomyRule[]>([])
  const [error, setError] = useState('')
  const timer = useRef<number | undefined>(undefined)

  const load = useCallback(() => {
    get<Dashboard>('/api/dashboard').then(setD).catch((e) => setError(e.message))
    get<CaseView[]>('/api/cases').then(setCases).catch(() => {})
    get<AutonomyRule[]>('/api/autonomy').then((r) => setSuggestions(r.filter((x) => x.suggestPromotion))).catch(() => {})
  }, [])

  useEffect(() => {
    load()
    return () => window.clearInterval(timer.current)
  }, [load])

  const poll = (id: string) => {
    window.clearInterval(timer.current)
    timer.current = window.setInterval(async () => {
      const r = await get<RunView>(`/api/agent/runs/${id}`)
      setRun(r)
      load()
      if (r.status !== 'RUNNING') window.clearInterval(timer.current)
    }, 1500)
  }

  const runAgent = async () => {
    setError('')
    try {
      const r = await post<RunView>('/api/agent/run')
      setRun(r)
      load()
      poll(r.id)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const running = run?.status === 'RUNNING'

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Cash at risk</h1>
          <p className="muted">Money you have earned but not yet received, and what the agent is doing about it.</p>
        </div>
        <button className="primary big" onClick={runAgent} disabled={running}>
          {running ? `Agent working… ${run!.done}/${run!.total}` : 'Run agent'}
        </button>
      </header>
      {error && <div className="alert error">{error}</div>}
      {run && run.status !== 'RUNNING' && (
        <div className="alert info">
          Agent run finished: {run.total === 0 ? 'nothing new to investigate.' : `investigated ${run.total} case(s).`}
        </div>
      )}
      {suggestions.map((s) => (
        <div className="alert success" key={s.actionType}>
          You approved <b>{s.approvalStreak}</b> “{s.actionType.toLowerCase().replace(/_/g, ' ')}” actions in a row.{' '}
          <Link to="/autonomy">Let the agent do these on its own?</Link>
        </div>
      ))}

      {d && (
        <section className="kpis">
          <Kpi title="Outstanding" value={money(d.outstanding)} />
          <Kpi title="Overdue" value={money(d.overdue)} tone="amber" />
          <Kpi title="Collected since agent engaged" value={money(d.collectedSinceAgent)} tone="green" />
          <Kpi title="Payments reconciled by agent" value={money(d.reconciledByAgent)} tone="green" />
          <Kpi title="Open cases" value={d.openExceptions} sub={`${d.monitoring} waiting on customer`} />
          <Kpi title="Needs your approval" value={d.waitingApproval} tone={d.waitingApproval ? 'amber' : undefined}
               link="/approvals" />
          <Kpi title="Escalated to you" value={d.escalated} tone={d.escalated ? 'red' : undefined} />
          <Kpi title="Guardrail blocks" value={d.guardrailBlocks} sub="unsafe actions stopped by policy" />
        </section>
      )}

      <section className="card">
        <h2>Latest cases</h2>
        {cases.length === 0 ? (
          <p className="muted">No cases yet. Click <b>Run agent</b> to scan every invoice.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Invoice</th><th>Customer</th><th>Amount</th><th>Problem</th><th>Root cause</th><th>Status</th></tr>
            </thead>
            <tbody>
              {cases.slice(0, 10).map((c) => (
                <tr key={c.id}>
                  <td><Link to={`/cases/${c.id}`}>{c.invoiceNumber}</Link></td>
                  <td>{c.customer}</td>
                  <td>{money(c.amount)}</td>
                  <td><Badge value={c.caseType} /></td>
                  <td>{c.rootCause ? <Badge value={c.rootCause} /> : <span className="muted">…</span>}</td>
                  <td><Badge value={c.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function Kpi(props: { title: string; value: string | number; sub?: string; tone?: string; link?: string }) {
  const body = (
    <div className={`kpi ${props.tone ?? ''}`}>
      <div className="kpi-title">{props.title}</div>
      <div className="kpi-value">{props.value}</div>
      {props.sub && <div className="kpi-sub">{props.sub}</div>}
    </div>
  )
  return props.link ? <Link to={props.link} className="kpi-link">{body}</Link> : body
}

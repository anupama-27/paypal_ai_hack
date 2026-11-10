import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { AgCharts } from 'ag-charts-react'
import { AllCommunityModule, type AgChartOptions } from 'ag-charts-community'
import { get, money, post, type AutonomyRule, type CaseView, type Dashboard, type InvoiceView, type RunView } from '../api'
import { Badge } from '../components/Badge'

export default function DashboardPage() {
  const [d, setD] = useState<Dashboard | null>(null)
  const [cases, setCases] = useState<CaseView[]>([])
  const [invoices, setInvoices] = useState<InvoiceView[]>([])
  const [run, setRun] = useState<RunView | null>(null)
  const [suggestions, setSuggestions] = useState<AutonomyRule[]>([])
  const [error, setError] = useState('')
  const timer = useRef<number | undefined>(undefined)

  const load = useCallback(() => {
    get<Dashboard>('/api/dashboard').then(setD).catch((e) => setError(e.message))
    get<CaseView[]>('/api/cases').then(setCases).catch(() => {})
    get<InvoiceView[]>('/api/invoices').then(setInvoices).catch(() => {})
    get<AutonomyRule[]>('/api/autonomy').then((r) => setSuggestions(r.filter((x) => x.suggestPromotion))).catch(() => {})
  }, [])

  // Outstanding money bucketed by how late it is (AG Charts).
  const agingChart = useMemo<AgChartOptions>(() => {
    const buckets = [
      { bucket: 'Not yet due', amount: 0 },
      { bucket: '1-30 days', amount: 0 },
      { bucket: '31-60 days', amount: 0 },
      { bucket: '60+ days', amount: 0 },
    ]
    for (const i of invoices) {
      if (i.outstanding <= 0) continue
      const b = i.daysOverdue <= 0 ? 0 : i.daysOverdue <= 30 ? 1 : i.daysOverdue <= 60 ? 2 : 3
      buckets[b].amount += i.outstanding
    }
    return {
      height: 260,
      data: buckets,
      series: [{ type: 'bar' as const, xKey: 'bucket', yKey: 'amount', yName: 'Outstanding' }],
      axes: {
        x: { type: 'category' as const, position: 'bottom' as const },
        y: { type: 'number' as const, position: 'left' as const,
          label: { formatter: (p: { value: number }) => money(p.value) } },
      },
    } as unknown as AgChartOptions
  }, [invoices])

  const caseChart = useMemo<AgChartOptions>(() => {
    const counts = new Map<string, number>()
    for (const c of cases) counts.set(c.status, (counts.get(c.status) ?? 0) + 1)
    return {
      height: 260,
      data: [...counts.entries()].map(([status, count]) => ({ status, count })),
      series: [{ type: 'donut' as const, angleKey: 'count', legendItemKey: 'status', innerRadiusRatio: 0.6 }],
    } as AgChartOptions
  }, [cases])

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

      <section className="charts">
        <div className="card">
          <h2>Outstanding by age</h2>
          <AgCharts options={agingChart} modules={AllCommunityModule} />
        </div>
        <div className="card">
          <h2>Cases by status</h2>
          {cases.length ? <AgCharts options={caseChart} modules={AllCommunityModule} /> : <p className="muted">No cases yet.</p>}
        </div>
      </section>

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

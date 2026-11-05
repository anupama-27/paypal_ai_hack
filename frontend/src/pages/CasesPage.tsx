import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { get, label, money, type CaseView } from '../api'
import { Badge } from '../components/Badge'

const FILTERS = ['ALL', 'OPEN', 'WAITING_APPROVAL', 'MONITORING', 'ESCALATED', 'RESOLVED']

export default function CasesPage() {
  const [cases, setCases] = useState<CaseView[]>([])
  const [filter, setFilter] = useState('ALL')

  useEffect(() => {
    get<CaseView[]>('/api/cases').then(setCases).catch(() => {})
  }, [])

  const shown = useMemo(() => cases.filter((c) => {
    if (filter === 'ALL') return true
    if (filter === 'OPEN') return c.status !== 'RESOLVED' && c.status !== 'ESCALATED'
    return c.status === filter
  }), [cases, filter])

  return (
    <div>
      <header className="page-header">
        <h1>Cases</h1>
        <div className="chips">
          {FILTERS.map((f) => (
            <button key={f} className={`chip ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>
              {label(f)}
            </button>
          ))}
        </div>
      </header>
      <section className="card">
        <table>
          <thead>
            <tr>
              <th>#</th><th>Invoice</th><th>Customer</th><th>Amount</th><th>Problem</th>
              <th>Root cause</th><th>Confidence</th><th>Status</th><th>Next check</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((c) => (
              <tr key={c.id}>
                <td><Link to={`/cases/${c.id}`}>{c.id}</Link></td>
                <td><Link to={`/cases/${c.id}`}>{c.invoiceNumber}</Link></td>
                <td>{c.customer}</td>
                <td>{money(c.amount)}</td>
                <td><Badge value={c.caseType} /></td>
                <td><Badge value={c.rootCause} /></td>
                <td>{c.confidence != null ? `${Math.round(c.confidence * 100)}%` : ''}</td>
                <td><Badge value={c.status} /></td>
                <td>{c.nextCheckAt ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {shown.length === 0 && <p className="muted">No cases match this filter.</p>}
      </section>
    </div>
  )
}

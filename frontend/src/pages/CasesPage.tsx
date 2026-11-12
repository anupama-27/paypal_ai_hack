import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
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

  const columns = useMemo<ColDef<CaseView>[]>(() => [
    { field: 'id', headerName: '#', width: 80, filter: false,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Link to={`/cases/${p.data!.id}`}>{p.value}</Link> },
    { field: 'invoiceNumber', headerName: 'Invoice', width: 120,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Link to={`/cases/${p.data!.id}`}>{p.value}</Link> },
    { field: 'customer', flex: 1 },
    { field: 'amount', valueFormatter: (p) => money(p.value), width: 120 },
    { field: 'caseType', headerName: 'Problem', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Badge value={p.value} /> },
    { field: 'rootCause', headerName: 'Root cause', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Badge value={p.value} /> },
    { field: 'confidence', headerName: 'Confidence', width: 120,
      valueFormatter: (p) => p.value != null ? `${Math.round(p.value * 100)}%` : '' },
    { field: 'status', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Badge value={p.value} /> },
    { field: 'nextCheckAt', headerName: 'Next check', width: 120 },
  ], [])

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
      <div className="card grid-wrap">
        <AgGridReact<CaseView> rowData={shown} columnDefs={columns} domLayout="autoHeight"
                               defaultColDef={{ filter: true, floatingFilter: true, sortable: true, resizable: true }} />
      </div>
    </div>
  )
}

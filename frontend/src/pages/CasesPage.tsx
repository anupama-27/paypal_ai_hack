import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import { get, label, money, type CaseView } from '../api'
import { Cause, Problem, StatusTrack } from '../components/Badge'
import { TableToolbar } from '../components/TableToolbar'

const FILTERS = ['ALL', 'OPEN', 'WAITING_APPROVAL', 'MONITORING', 'ESCALATED', 'RESOLVED']

export default function CasesPage() {
  const [cases, setCases] = useState<CaseView[]>([])
  const [filter, setFilter] = useState('ALL')
  const [search, setSearch] = useState('')

  useEffect(() => {
    get<CaseView[]>('/api/cases').then(setCases).catch(() => {})
  }, [])

  const matchesFilter = (c: CaseView, f: string) =>
    f === 'ALL' || (f === 'OPEN' ? c.status !== 'RESOLVED' && c.status !== 'ESCALATED' : c.status === f)

  const shown = useMemo(() => {
    const q = search.trim().toLowerCase()
    return cases.filter((c) => matchesFilter(c, filter)
      && (!q || [String(c.id), c.invoiceNumber, c.customer, c.caseType, c.rootCause, c.status].some((v) => (v ?? '').toLowerCase().includes(q))))
  }, [cases, filter, search])

  const columns = useMemo<ColDef<CaseView>[]>(() => [
    { field: 'id', headerName: '#', width: 80, filter: false,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Link to={`/cases/${p.data!.id}`}>{p.value}</Link> },
    { field: 'invoiceNumber', headerName: 'Invoice', width: 120,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Link to={`/cases/${p.data!.id}`}>{p.value}</Link> },
    { field: 'customer', flex: 1 },
    { field: 'amount', valueFormatter: (p) => money(p.value), width: 120 },
    { field: 'caseType', headerName: 'Problem', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Problem value={p.value} /> },
    { field: 'rootCause', headerName: 'Root cause', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <Cause value={p.value} status={p.data?.status} /> },
    { field: 'confidence', headerName: 'Confidence', width: 140,
      valueFormatter: (p) => p.value != null ? `${Math.round(p.value * 100)}%` : '' },
    { field: 'status', width: 170,
      cellRenderer: (p: ICellRendererParams<CaseView>) => <StatusTrack value={p.value} /> },
    { field: 'nextCheckAt', headerName: 'Next check', width: 120 },
  ], [])

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Cases</h1>
          <p className="muted">Every invoice problem the agent has detected, with its diagnosed cause and where it stands.</p>
        </div>
      </header>
      <TableToolbar
        search={search} onSearch={setSearch} placeholder="Search case, invoice, customer or cause…"
        filter={filter} onFilter={setFilter} shown={shown.length} total={cases.length} noun="cases"
        options={FILTERS.map((f) => ({ value: f, label: label(f), count: cases.filter((c) => matchesFilter(c, f)).length }))} />
      <div className="card grid-wrap">
        <AgGridReact<CaseView> rowData={shown} columnDefs={columns} domLayout="autoHeight"
                               defaultColDef={{ filter: true, sortable: true, resizable: true, wrapHeaderText: true, autoHeaderHeight: true }}
                               overlayNoRowsTemplate="<span>No cases match your search.</span>" />
      </div>
    </div>
  )
}

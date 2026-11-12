import { useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import { get, money, type CustomerInvoice } from '../api'
import type { AuthUser } from '../auth'
import { Badge } from '../components/Badge'

/** What a billed customer sees after signing in: only invoices addressed to their email. */
export default function CustomerPage({ user }: { user: AuthUser }) {
  const [rows, setRows] = useState<CustomerInvoice[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    get<CustomerInvoice[]>('/api/customer/invoices').then(setRows).catch((e) => setError(e.message))
  }, [])

  const columns = useMemo<ColDef<CustomerInvoice>[]>(() => [
    { field: 'invoiceNumber', headerName: 'Invoice', width: 130 },
    { field: 'seller', headerName: 'From', flex: 1 },
    { field: 'amount', valueFormatter: (p) => money(p.value, p.data?.currency), width: 120 },
    { field: 'outstanding', valueFormatter: (p) => money(p.value, p.data?.currency), width: 130 },
    { field: 'dueDate', headerName: 'Due', width: 120 },
    { field: 'status', width: 150, cellRenderer: (p: ICellRendererParams<CustomerInvoice>) => <Badge value={p.value} /> },
    {
      headerName: 'Actions', width: 220, filter: false, sortable: false,
      cellRenderer: (p: ICellRendererParams<CustomerInvoice>) => (
        <span className="row">
          <a href={p.data!.fixAndPayLink} target="_blank" rel="noreferrer">Fix &amp; Pay</a>
          {p.data!.payUrl && p.data!.status !== 'PAID' && (
            <a href={p.data!.payUrl} target="_blank" rel="noreferrer">Pay on PayPal</a>
          )}
        </span>
      ),
    },
  ], [])

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Your invoices</h1>
          <p className="muted">Invoices billed to {user.email} by every seller. Open Fix &amp; Pay to add a PO, report a payment or ask for a plan.</p>
        </div>
      </header>
      {error && <div className="alert error">{error}</div>}
      <div className="card grid-wrap">
        <AgGridReact<CustomerInvoice> rowData={rows} columnDefs={columns} domLayout="autoHeight"
                                      defaultColDef={{ filter: true, floatingFilter: true, sortable: true, resizable: true }} />
      </div>
      {!error && rows.length === 0 && <p className="muted">No invoices are addressed to you yet.</p>}
    </div>
  )
}

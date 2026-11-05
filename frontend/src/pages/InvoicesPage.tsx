import { useCallback, useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import { get, money, post, type Dashboard, type InvoiceView } from '../api'
import { Badge } from '../components/Badge'

export default function InvoicesPage() {
  const [rows, setRows] = useState<InvoiceView[]>([])
  const [paypalEnabled, setPaypalEnabled] = useState(false)
  const [message, setMessage] = useState('')

  const load = useCallback(() => {
    get<InvoiceView[]>('/api/invoices').then(setRows).catch((e) => setMessage(e.message))
    get<Dashboard>('/api/dashboard').then((d) => setPaypalEnabled(d.paypalEnabled)).catch(() => {})
  }, [])
  useEffect(load, [load])

  const call = useCallback(async (path: string, done: string) => {
    setMessage('')
    try {
      await post(path)
      setMessage(done)
      load()
    } catch (e) {
      setMessage((e as Error).message)
    }
  }, [load])

  const columns = useMemo<ColDef<InvoiceView>[]>(() => [
    { field: 'invoiceNumber', headerName: 'Invoice', width: 110 },
    { field: 'customer', flex: 1 },
    { field: 'amount', valueFormatter: (p) => money(p.value), width: 120 },
    { field: 'paid', valueFormatter: (p) => money(p.value), width: 120 },
    { field: 'outstanding', valueFormatter: (p) => money(p.value), width: 130, sort: 'desc' },
    { field: 'dueDate', headerName: 'Due', width: 120 },
    { field: 'daysOverdue', headerName: 'Days late', width: 110 },
    { field: 'status', width: 150, cellRenderer: (p: ICellRendererParams<InvoiceView>) => <Badge value={p.value} /> },
    { field: 'purchaseOrder', headerName: 'PO', width: 120 },
    {
      headerName: 'PayPal', width: 200,
      cellRenderer: (p: ICellRendererParams<InvoiceView>) => {
        const i = p.data!
        if (i.paypalInvoiceId) {
          return (
            <span className="row">
              {i.paypalPayerUrl ? <a href={i.paypalPayerUrl} target="_blank" rel="noreferrer">View</a> : i.paypalInvoiceId}
              <button className="link" onClick={() => call(`/api/invoices/${i.id}/paypal-refresh`, `Synced ${i.invoiceNumber} from PayPal`)}>Sync</button>
            </span>
          )
        }
        return paypalEnabled && i.status !== 'PAID'
          ? <button className="link" onClick={() => call(`/api/invoices/${i.id}/paypal-sync`, `${i.invoiceNumber} sent through PayPal`)}>Send via PayPal</button>
          : <span className="muted">not published</span>
      },
    },
    {
      headerName: 'Customer link', width: 130,
      cellRenderer: (p: ICellRendererParams<InvoiceView>) =>
        <a href={p.data!.fixAndPayLink} target="_blank" rel="noreferrer">Fix &amp; Pay</a>,
    },
  ], [paypalEnabled, call])

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Invoices</h1>
          <p className="muted">Every invoice, with payments matched from PayPal.</p>
        </div>
      </header>
      {message && <div className="alert info">{message}</div>}
      <div className="card grid-wrap">
        <AgGridReact<InvoiceView> rowData={rows} columnDefs={columns} domLayout="autoHeight" />
      </div>
    </div>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import { themeQuartz, type ColDef, type GridApi, type ICellRendererParams } from 'ag-grid-community'
import { get, money, post, type Dashboard, type InvoiceView } from '../api'
import { Badge } from '../components/Badge'

// Tighter cells so all columns fit the page without a horizontal scrollbar.
// Columns hidden as the grid gets narrower, least important first.
const HIDE_BELOW: [number, string[]][] = [
  [1000, ['purchaseOrder', 'daysOverdue']],
  [800, ['paypal', 'paid']],
  [600, ['dueDate', 'amount']],
  [480, ['status']],
  [400, ['outstanding']],
]

function fitColumns(api: GridApi) {
  const width = api.getDisplayedCenterColumns().length ? document.querySelector('.grid-wrap')?.clientWidth ?? 1200 : 1200
  const hidden = HIDE_BELOW.filter(([max]) => width < max).flatMap(([, ids]) => ids)
  const all = HIDE_BELOW.flatMap(([, ids]) => ids)
  api.setColumnsVisible(all.filter((id) => !hidden.includes(id)), true)
  api.setColumnsVisible(hidden, false)
  api.sizeColumnsToFit()
}

const compactTheme = themeQuartz.withParams({ cellHorizontalPadding: 8, headerFontSize: 13, fontSize: 13 })

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
    { field: 'invoiceNumber', headerName: 'Invoice', flex: 85, minWidth: 70 },
    { field: 'customer', flex: 115, minWidth: 80 },
    { field: 'amount', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 70 },
    { field: 'paid', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 70 },
    { field: 'outstanding', valueFormatter: (p) => money(p.value), flex: 100, minWidth: 85, sort: 'desc' },
    { field: 'dueDate', headerName: 'Due', flex: 115, minWidth: 95 },
    { field: 'daysOverdue', headerName: 'Days late', flex: 80, minWidth: 60 },
    { field: 'status', flex: 100, minWidth: 150, cellRenderer: (p: ICellRendererParams<InvoiceView>) => <Badge value={p.value} /> },
    { field: 'purchaseOrder', headerName: 'PO', flex: 45, minWidth: 55 },
    {
      colId: 'paypal', headerName: 'PayPal', flex: 115, minWidth: 110,
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
      colId: 'link', headerName: 'Customer link', flex: 100, minWidth: 80,
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
        <AgGridReact<InvoiceView>
          theme={compactTheme}
          rowData={rows}
          columnDefs={columns}
          domLayout="autoHeight"
          onGridReady={(e) => fitColumns(e.api)}
          onGridSizeChanged={(e) => fitColumns(e.api)}
          onFirstDataRendered={(e) => fitColumns(e.api)}
        />
      </div>
    </div>
  )
}


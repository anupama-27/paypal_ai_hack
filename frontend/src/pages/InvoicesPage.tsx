import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
  const dialogRef = useRef<HTMLDialogElement>(null)
  const gridRef = useRef<AgGridReact<InvoiceView>>(null)
  const [emailFilter, setEmailFilter] = useState('')
  const [formError, setFormError] = useState('')
  const [form, setForm] = useState({ invoiceNumber: '', customerName: '', customerEmail: '', amount: '', paidAmount: '', dueDate: '', purchaseOrder: '' })

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

  const create = async () => {
    setFormError('')
    if (!form.customerName.trim() || !form.customerEmail.trim() || !form.dueDate) {
      setFormError('Customer name, email and due date are required')
      return
    }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.customerEmail)) {
      setFormError('Enter a valid customer email address')
      return
    }
    if (form.paidAmount && Number(form.paidAmount) > Number(form.amount)) {
      setFormError('Amount already paid cannot exceed the invoice amount')
      return
    }
    try {
      await post('/api/invoices', {
        ...form,
        amount: Number(form.amount),
        paidAmount: form.paidAmount ? Number(form.paidAmount) : null,
        dueDate: form.dueDate || null,
        purchaseOrder: form.purchaseOrder || null,
      })
      setFormError('')
      setMessage(`Invoice ${form.invoiceNumber} created`)
      dialogRef.current?.close()
      setForm({ invoiceNumber: '', customerName: '', customerEmail: '', amount: '', paidAmount: '', dueDate: '', purchaseOrder: '' })
      load()
    } catch (e) {
      setFormError((e as Error).message)
    }
  }

  const columns = useMemo<ColDef<InvoiceView>[]>(() => [
    { field: 'invoiceNumber', headerName: 'Invoice', flex: 85, minWidth: 70 },
    { field: 'customer', flex: 115, minWidth: 80 },
    { field: 'customerEmail', headerName: 'Email', flex: 1 },
    { field: 'amount', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 70 },
    { field: 'paid', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 70 },
    { field: 'outstanding', valueFormatter: (p) => money(p.value), flex: 100, minWidth: 85, sort: 'desc' },
    { field: 'dueDate', headerName: 'Due', flex: 115, minWidth: 95 },
    { field: 'daysOverdue', headerName: 'Days late', flex: 80, minWidth: 60 },
    { field: 'status', flex: 100, minWidth: 100, cellRenderer: (p: ICellRendererParams<InvoiceView>) => <Badge value={p.value} /> },
    { field: 'purchaseOrder', headerName: 'PO', flex: 45, minWidth: 55 },
    {
      colId: 'paypal', headerName: 'PayPal', flex: 115, minWidth: 110, filter: false,
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
      colId: 'link', headerName: 'Customer link', flex: 100, minWidth: 80, filter: false,
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
        <button className="primary" onClick={() => dialogRef.current?.showModal()}>New invoice</button>
      </header>
      {message && <div className="alert info">{message}</div>}
      <dialog ref={dialogRef} className="modal" onClick={(e) => { if (e.target === dialogRef.current) dialogRef.current.close() }}>
        <h2>New invoice</h2>
        {formError && <div className="alert error">{formError}</div>}
        <div className="form">
          <div className="modal-fields">
            <label>Invoice number *
              <input placeholder="INV-112" value={form.invoiceNumber}
                     onChange={(e) => setForm({ ...form, invoiceNumber: e.target.value })} /></label>
            <label>Customer name *
              <input placeholder="Acme Inc." value={form.customerName}
                     onChange={(e) => setForm({ ...form, customerName: e.target.value })} /></label>
            <label>Customer email *
              <input type="email" placeholder="ap@acme.example" value={form.customerEmail}
                     onChange={(e) => setForm({ ...form, customerEmail: e.target.value })} /></label>
            <label>Amount (USD) *
              <input type="number" min="0.01" step="0.01" placeholder="1200.00" value={form.amount}
                     onChange={(e) => setForm({ ...form, amount: e.target.value })} /></label>
            <label>Already paid (optional)
              <input type="number" min="0" step="0.01" placeholder="0.00 — leave empty if unpaid" value={form.paidAmount}
                     onChange={(e) => setForm({ ...form, paidAmount: e.target.value })} /></label>
            <label>Due date *
              <input type="date" value={form.dueDate}
                     onChange={(e) => setForm({ ...form, dueDate: e.target.value })} /></label>
            <label>Purchase order (optional)
              <input placeholder="PO-12345" value={form.purchaseOrder}
                     onChange={(e) => setForm({ ...form, purchaseOrder: e.target.value })} /></label>
          </div>
          <div className="row">
            <button className="primary" onClick={create}
                    disabled={!form.invoiceNumber.trim() || !form.customerName.trim() || !form.customerEmail.trim()
                      || !form.amount || Number(form.amount) <= 0 || !form.dueDate}>
              Create invoice</button>
            <button onClick={() => dialogRef.current?.close()}>Cancel</button>
          </div>
        </div>
      </dialog>
      <div className="row" style={{ marginBottom: 10 }}>
        <input placeholder="Filter by customer email…" value={emailFilter} style={{ minWidth: 260 }}
               onChange={(e) => {
                 setEmailFilter(e.target.value)
                 gridRef.current?.api.setGridOption('quickFilterText', e.target.value)
               }} />
      </div>
      <div className="card grid-wrap">
        <AgGridReact<InvoiceView>
          theme={compactTheme}
          rowData={rows}
          columnDefs={columns}
          domLayout="autoHeight"
          defaultColDef={{ filter: true, floatingFilter: true, sortable: true, resizable: true }}
          onGridReady={(e) => fitColumns(e.api)}
          onGridSizeChanged={(e) => fitColumns(e.api)}
          onFirstDataRendered={(e) => fitColumns(e.api)}
        />
      </div>
    </div>
  )
}


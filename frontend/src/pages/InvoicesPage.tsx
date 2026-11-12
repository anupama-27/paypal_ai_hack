import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import { themeQuartz, type ColDef, type GridApi, type ICellRendererParams } from 'ag-grid-community'
import { get, label, money, post, type Dashboard, type InvoiceView } from '../api'
import { Badge } from '../components/Badge'
import { TableToolbar } from '../components/TableToolbar'

// Tighter cells so all columns fit the page without a horizontal scrollbar.
// Columns hidden as the grid gets narrower, least important first.
const HIDE_BELOW: [number, string[]][] = [
  [1340, ['purchaseOrder']],
  [1260, ['customerEmail']],
  [1130, ['daysOverdue']],
  [1035, ['paid']],
  [945, ['paypal']],
  [820, ['dueDate']],
  [715, ['amount']],
  [600, ['status']],
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
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [formError, setFormError] = useState('')
  const [form, setForm] = useState({ invoiceNumber: '', customerName: '', customerEmail: '', amount: '', paidAmount: '', dueDate: '', purchaseOrder: '' })

  const load = useCallback(() => {
    get<InvoiceView[]>('/api/invoices').then(setRows).catch((e) => setMessage(e.message))
    get<Dashboard>('/api/dashboard').then((d) => setPaypalEnabled(d.paypalEnabled)).catch(() => {})
  }, [])
  useEffect(load, [load])

  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const r of rows) counts[r.status] = (counts[r.status] ?? 0) + 1
    return counts
  }, [rows])

  const shown = useMemo(() => {
    const q = search.trim().toLowerCase()
    return rows.filter((r) =>
      (statusFilter === 'ALL' || r.status === statusFilter)
      && (!q || [r.invoiceNumber, r.customer, r.customerEmail, r.purchaseOrder, r.status].some((v) => (v ?? '').toLowerCase().includes(q))))
  }, [rows, search, statusFilter])

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
    { field: 'invoiceNumber', headerName: 'Invoice', flex: 85, minWidth: 105 },
    { field: 'customer', flex: 115, minWidth: 120 },
    { field: 'customerEmail', headerName: 'Email', flex: 130, minWidth: 130 },
    { field: 'amount', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 105 },
    { field: 'paid', valueFormatter: (p) => money(p.value), flex: 85, minWidth: 90 },
    { field: 'outstanding', valueFormatter: (p) => money(p.value), flex: 100, minWidth: 155, sort: 'desc' },
    { field: 'dueDate', headerName: 'Due', flex: 110, minWidth: 105 },
    { field: 'daysOverdue', headerName: 'Days late', flex: 80, minWidth: 95 },
    { field: 'status', flex: 100, minWidth: 125, cellRenderer: (p: ICellRendererParams<InvoiceView>) => <Badge value={p.value} /> },
    { field: 'purchaseOrder', headerName: 'PO', flex: 45, minWidth: 80 },
    {
      colId: 'paypal', headerName: 'PayPal', flex: 115, minWidth: 125, filter: false,
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
      colId: 'link', headerName: 'Customer link', flex: 100, minWidth: 105, filter: false,
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
      <TableToolbar
        search={search} onSearch={setSearch} placeholder="Search invoice, customer, email or PO…"
        filter={statusFilter} onFilter={setStatusFilter} shown={shown.length} total={rows.length} noun="invoices"
        options={[
          { value: 'ALL', label: 'All', count: rows.length },
          ...['OVERDUE', 'SENT', 'PARTIALLY_PAID', 'PAID'].map((v) => ({ value: v, label: label(v), count: statusCounts[v] ?? 0 })),
        ]} />
      <div className="card grid-wrap">
        <AgGridReact<InvoiceView>
          theme={compactTheme}
          rowData={shown}
          columnDefs={columns}
          domLayout="autoHeight"
          defaultColDef={{ filter: true, sortable: true, resizable: true, wrapHeaderText: true, autoHeaderHeight: true }}
          overlayNoRowsTemplate="<span>No invoices match your search.</span>"
          onGridReady={(e) => fitColumns(e.api)}
          onGridSizeChanged={(e) => fitColumns(e.api)}
          onFirstDataRendered={(e) => fitColumns(e.api)}
        />
      </div>
    </div>
  )
}


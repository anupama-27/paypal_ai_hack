import { useCallback, useEffect, useState } from 'react'
import { MCP_URL, get, post, type DemoState, type InvoiceView } from '../api'

export default function DemoPage() {
  const [state, setState] = useState<DemoState | null>(null)
  const [invoices, setInvoices] = useState<InvoiceView[]>([])
  const [message, setMessage] = useState('')
  const [pay, setPay] = useState({ invoiceNumber: 'INV-101', amount: '', reference: '', paidOnInvoice: true })

  const load = useCallback(() => {
    get<DemoState>('/api/demo/state').then(setState).catch((e) => setMessage(e.message))
    get<InvoiceView[]>('/api/invoices').then(setInvoices).catch(() => {})
  }, [])
  useEffect(load, [load])

  const run = async (path: string, body?: unknown, done?: (r: never) => string) => {
    setMessage('')
    try {
      const r = await post<never>(path, body)
      setMessage(done ? done(r) : 'Done')
      load()
    } catch (e) {
      setMessage((e as Error).message)
    }
  }

  const acme = state?.customers.find((c) => c.email === 'ap@acme.example')
  const mcpConfig = acme && JSON.stringify({
    mcpServers: {
      'acme-accounts-payable': {
        command: 'npx',
        args: ['-y', 'mcp-remote', MCP_URL, '--header', 'Authorization:${AUTH_HEADER}'],
        env: { AUTH_HEADER: `Bearer ${acme.accessKey}` },
      },
    },
  }, null, 2)

  return (
    <div>
      <header className="page-header">
        <div>
          <h1>Demo controls</h1>
          <p className="muted">Reset the story, move time forward, and stand in for PayPal or the customer.</p>
        </div>
      </header>
      {message && <div className="alert info">{message}</div>}

      <div className="grid-2">
        <section className="card">
          <h2>Data and time</h2>
          <p>Business date: <b>{state?.today}</b> {state && state.offsetDays > 0 && <span className="muted">(+{state.offsetDays} days)</span>}</p>
          <div className="row">
            <button onClick={() => run('/api/demo/reset', undefined, () => 'Demo data reset')}>Reset demo data</button>
            {[1, 3, 7].map((d) => (
              <button key={d} onClick={() => run(`/api/demo/advance?days=${d}`, undefined,
                (r: DemoState) => `Moved ${d} day(s) ahead. ${r.followUpsReopened ?? 0} case(s) handed back to the agent.`)}>
                +{d} day{d > 1 ? 's' : ''}
              </button>
            ))}
          </div>
          {state?.paypalEnabled && (
            <div className="row">
              <button className="primary" onClick={() => run('/api/demo/publish-to-paypal', undefined,
                (r: { published: number }) => `Published ${r.published} invoice(s) to the PayPal sandbox`)}>
                Publish all invoices to PayPal sandbox
              </button>
            </div>
          )}
        </section>

        <section className="card">
          <h2>Simulate a payment</h2>
          <p className="muted small">
            Use this when PayPal is not configured. “Paid on the invoice” behaves like PayPal's invoice-paid webhook;
            unticked creates a loose payment the agent has to find.
          </p>
          <div className="form">
            <label>Invoice
              <select value={pay.invoiceNumber} onChange={(e) => setPay({ ...pay, invoiceNumber: e.target.value })}>
                {invoices.map((i) => <option key={i.id} value={i.invoiceNumber}>{i.invoiceNumber} · {i.customer}</option>)}
              </select>
            </label>
            <label>Amount<input value={pay.amount} placeholder="e.g. 1200.00" onChange={(e) => setPay({ ...pay, amount: e.target.value })} /></label>
            <label>Payer's reference<input value={pay.reference} placeholder="defaults to the invoice number" onChange={(e) => setPay({ ...pay, reference: e.target.value })} /></label>
            <label className="check">
              <input type="checkbox" checked={pay.paidOnInvoice} onChange={(e) => setPay({ ...pay, paidOnInvoice: e.target.checked })} />
              Paid on the invoice
            </label>
            <button className="primary" onClick={() => run('/api/demo/payments', {
              ...pay, amount: Number(pay.amount), reference: pay.reference || null,
            }, () => 'Payment recorded')}>Record payment</button>
          </div>
        </section>
      </div>

      <section className="card">
        <h2>Customer side</h2>
        <p className="muted">Each customer gets a Fix &amp; Pay link per invoice, and an access key for their own AI agent.</p>
        <table>
          <thead><tr><th>Customer</th><th>Fix &amp; Pay links</th><th>Agent access key</th></tr></thead>
          <tbody>
            {state?.customers.map((c) => (
              <tr key={c.email}>
                <td>{c.name}<div className="muted small">{c.email}</div></td>
                <td>{c.fixAndPayLinks.map((l) => (
                  <div key={l.invoice}><a href={l.link} target="_blank" rel="noreferrer">{l.invoice}</a></div>
                ))}</td>
                <td><code className="small">{c.accessKey}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {mcpConfig && (
        <section className="card">
          <h2>Connect Acme's AI agent (MCP)</h2>
          <p>
            Play the customer's accounts-payable assistant: add this to Claude Desktop
            (<i>Settings → Developer → Edit config</i>), restart it, and ask:
          </p>
          <blockquote>
            “You are Acme's AP assistant. Check what we owe this vendor, add PO-88421 to INV-101, and try to split
            INV-111 into 3 payments over 90 days.”
          </blockquote>
          <pre className="json">{mcpConfig}</pre>
          <p className="muted small">MCP endpoint: <code>{MCP_URL}</code> (Streamable HTTP, bearer key per customer).</p>
        </section>
      )}
    </div>
  )
}

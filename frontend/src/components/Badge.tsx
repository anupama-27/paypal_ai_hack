import { label } from '../api'

const TONE: Record<string, string> = {
  RESOLVED: 'green', PAID: 'green', EXECUTED: 'green', KEPT: 'green', AUTO: 'green', DONE: 'green',
  MONITORING: 'blue', SENT: 'blue', INVESTIGATING: 'blue', DETECTED: 'blue', PENDING: 'blue', RUNNING: 'blue',
  WAITING_APPROVAL: 'amber', PENDING_APPROVAL: 'amber', PARTIALLY_PAID: 'amber', APPROVAL: 'amber', OVERDUE: 'amber',
  ESCALATED: 'red', BLOCKED: 'red', REJECTED: 'red', FAILED: 'red', DISPUTED: 'red', BROKEN: 'red',
}

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) return null
  return <span className={`badge ${TONE[value] ?? 'grey'}`}>{label(value)}</span>
}

const PROBLEM_TONE: Record<string, string> = {
  OVERDUE_INVOICE: 'amber', PARTIAL_PAYMENT: 'amber', RECONCILIATION_MISMATCH: 'blue',
  CUSTOMER_REQUEST: 'blue', BROKEN_PROMISE: 'red', DISPUTE: 'red',
}

/** What was detected: plain text with a coloured rule in the margin. */
export function Problem({ value }: { value: string | null | undefined }) {
  if (!value) return null
  return <span className={`problem ${PROBLEM_TONE[value] ?? 'grey'}`}>{label(value)}</span>
}

/** The agent's diagnosis. Quiet text, because it is a sentence rather than a state. */
export function Cause({ value, status }: { value: string | null | undefined; status?: string }) {
  if (!value) {
    const working = status === 'DETECTED' || status === 'INVESTIGATING'
    return <span className="cause pending">{working ? 'Analysing…' : '—'}</span>
  }
  if (value === 'UNKNOWN') return <span className="cause pending">Not determined</span>
  return <span className="cause">{label(value)}</span>
}

/** Case status as plain coloured text. */
export function StatusTrack({ value }: { value: string | null | undefined }) {
  if (!value) return null
  const tone = value === 'RESOLVED' ? 'done' : value === 'ESCALATED' ? 'esc' : value === 'WAITING_APPROVAL' ? 'wait' : 'live'
  return <span className={`track ${tone}`}>{label(value)}</span>
}

const ACTOR: Record<string, string> = {
  AGENT: 'AI agent',
  OWNER: 'Owner',
  CUSTOMER_PORTAL: 'Customer (Fix & Pay)',
  CUSTOMER_AGENT: "Customer's AI agent",
  PAYPAL: 'PayPal',
  SYSTEM: 'System',
}

export function Actor({ value }: { value: string }) {
  return <span className={`actor actor-${value.toLowerCase()}`}>{ACTOR[value] ?? value}</span>
}

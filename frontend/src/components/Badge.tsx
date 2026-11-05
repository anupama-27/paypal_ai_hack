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

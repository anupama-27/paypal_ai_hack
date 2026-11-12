const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''

export const MCP_URL = `${BASE || 'http://localhost:8080'}/mcp`

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      message = body.message || body.error || message
    } catch {
      // response had no JSON body
    }
    throw new Error(message)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

export const get = <T>(path: string) => request<T>(path)
export const post = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export const put = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })

export interface Dashboard {
  outstanding: number
  overdue: number
  collectedSinceAgent: number
  reconciledByAgent: number
  openExceptions: number
  monitoring: number
  waitingApproval: number
  escalated: number
  resolved: number
  guardrailBlocks: number
  autonomySuggestions: number
  agentBrain: string
  paypalEnabled: boolean
  today: string
}

export interface CaseView {
  id: number
  invoiceId: number
  invoiceNumber: string
  customer: string
  amount: number
  invoiceStatus: string
  caseType: string
  status: string
  rootCause: string | null
  confidence: number | null
  recommendedAction: string | null
  explanation: string | null
  nextCheckAt: string | null
  createdAt: string
  resolvedAt: string | null
}

export interface AgentAction {
  id: number
  caseId: number
  toolName: string
  actionType: string | null
  status: string
  actor: string
  toolInput: string | null
  toolOutput: string | null
  requiresApproval: boolean
  policyReason: string | null
  approvedBy: string | null
  createdAt: string
  executedAt: string | null
}

export interface Message {
  from: string
  at: string
  text: string
}

export interface Promise_ {
  amount: string
  due: string
  status: string
  source: string
}

export interface InvoiceSummary {
  invoice_number: string
  customer: string
  amount: string
  paid: string
  outstanding: string
  currency: string
  due_date: string
  days_overdue: number
  status: string
  purchase_order: string | null
  pay_url: string | null
  promises: Promise_[]
  messages: Message[]
}

export interface CaseDetail {
  caseInfo: CaseView
  invoice: InvoiceSummary
  actions: AgentAction[]
  fixAndPayLink: string
}

export interface PendingAction {
  action: AgentAction
  caseInfo: CaseView
}

export interface RunView {
  id: string
  status: 'RUNNING' | 'DONE' | 'FAILED'
  total: number
  done: number
  caseIds: number[]
  error: string | null
}

export interface InvoiceView {
  id: number
  invoiceNumber: string
  customer: string
  customerEmail: string | null
  amount: number
  paid: number
  outstanding: number
  currency: string
  dueDate: string
  daysOverdue: number
  status: string
  purchaseOrder: string | null
  paypalInvoiceId: string | null
  paypalPayerUrl: string | null
  fixAndPayLink: string
}

export interface TimelineEvent {
  kind: 'INVOICE_DUE' | 'PROMISE_DUE' | 'FOLLOW_UP'
  sourceId: number
  date: string
  label: string
  invoiceNumber: string | null
  caseId: number | null
  amount: number | null
  status: string
  time: string
}

export interface AutonomyRule {
  actionType: string
  mode: 'AUTO' | 'APPROVAL'
  approvalStreak: number
  suggestPromotion: boolean
}

export interface DemoState {
  today: string
  offsetDays: number
  paypalEnabled: boolean
  agentBrain: string
  customers: { name: string; email: string; accessKey: string; fixAndPayLinks: { invoice: string; link: string }[] }[]
  followUpsReopened?: number
}

export interface PortalView extends InvoiceSummary {
  plan_limits: { max_installments: number; max_days: number; min_first_share: number }
}

export interface ResolutionResult {
  ok: boolean
  status: string
  message: string
  data: Record<string, unknown>
}

export const money = (value: number | string | null | undefined, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(Number(value ?? 0))

export const when = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : ''

export const label = (value: string | null | undefined) =>
  (value ?? '').toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase()).replace(/\bpo\b/gi, 'PO')

# CashFlow AI: self-healing accounts receivable on PayPal

CashFlow AI is an AI agent for small businesses that invoice through PayPal. It finds invoices where money is stuck, works out **why**, takes the safest next action through PayPal, and keeps going until the invoice is resolved.

Detect → Understand → Act → Verify → Reconcile → Resolve

Built for the *Build What's Next with PayPal and AI* hackathon.

## How it works

| Layer | Responsibility |
|---|---|
| **Exception detection (Java)** | Deterministic rules decide *that* something is wrong: overdue, payment not reconciled, partial payment, or a broken promise to pay. |
| **AI agent (LLM + tools)** | Decides *why* (missing PO, no response, unrecorded payment, discount request, and so on) and picks one action. It only acts through tools. |
| **Policy engine (Java)** | Every tool call is checked first. Safe actions run automatically. PO changes, refunds and write-offs need a human; discounts are never allowed. |
| **Paid-check guard** | The agent cannot send a reminder until it has checked PayPal payments for this invoice (exact reference, a typo of it, or same payer and exact amount). If a payment exists it reconciles instead of chasing. Disputed invoices are never chased. |
| **Closed loop (PayPal webhooks)** | Signature-verified, idempotent webhooks pull the payment from PayPal, link it, keep promises, and resolve the case. Cases waiting on the customer are re-checked on their follow-up date. |
| **Fix & Pay page** | Every reminder links to a page where the customer adds a missing PO, reports a payment, agrees a payment plan inside the owner's limits (PayPal partial payments), promises a date, or disputes. |
| **Agent-readable invoices (MCP)** | The customer's own AI agent can connect to `/mcp` with a per-customer key and do the same things in a structured way. Same policy engine, same audit trail. |
| **Earned autonomy** | PO updates, late reminders and partial matches start as "needs approval". After a streak of approvals the owner can hand them to the agent; one rejection takes it back. |
| **Action service (Java + PayPal)** | Performs the action: PayPal reminder, PO update and resend, partial-payment config, or reconciliation. All money math uses `BigDecimal` in Java. The AI never calculates amounts. |
| **Audit trail** | Every tool call, policy decision, customer action, PayPal event and approval is stored in `agent_action`, with who did it. |

### Demo scenarios (seeded on startup)

| Invoice | Situation | What happens |
|---|---|---|
| INV-101 Acme, $4,500 | Customer says the PO number is missing | Diagnoses MISSING_PO and proposes adding PO-88421. **Waits for approval**, or the customer adds it on the Fix & Pay page and it is applied straight away. |
| INV-102 Nova Labs, $1,200 | 8 days overdue, no reply | Checks for a payment first, then sends a reminder with the Fix & Pay link. Follows up in 3 days. |
| INV-103 Brightside, $800 | Paid in PayPal, books still show it open | Finds the payment and auto-reconciles it (exact match). **Self-healed.** |
| INV-104 Orbit, $2,500 | $2,000 received | Calculates the $500 balance in Java and reminds the customer. |
| INV-105 Lumen, $6,400 | 65 days overdue | Escalates to the finance team. |
| INV-109 Harbor, $1,500 | Looks overdue, but the customer paid with reference "INV-1O9" | **Paid-check guard**: matches the typo, reconciles, never sends a reminder. |
| INV-110 Summit, $3,200 | Customer asks for 20% off | Agent tries `offer_discount`, the **policy blocks it**, and the case is escalated. |
| INV-111 Acme, $2,000 | Not yet due | Used by Acme's AI agent over MCP to negotiate a payment plan. |

Customer replies are simulated, and all customers are fictional.

## Run it locally

Requirements: Java 21 and Maven (or IntelliJ IDEA, which has Maven built in) and a PostgreSQL database.

```bash
git clone https://github.com/anupama-27/paypal_ai_hack.git
cd paypal_ai_hack
cp .env.example .env        # fill in values (optional at first)

# PostgreSQL — either docker:
docker compose up -d        # creates the cashflow + cashflow_test databases
# ...or an existing server: create a 'cashflow' database and a user that owns it,
# then set DATABASE_URL / DATABASE_USERNAME / DATABASE_PASSWORD in .env

mvn spring-boot:run
```

It runs on `http://localhost:8080` backed by PostgreSQL. `src/main/resources/db/schema.sql`
creates the tables on startup and the demo scenarios are seeded when the tables are empty.
Local defaults: `jdbc:postgresql://localhost:5432/cashflow`, user/password `cashflow`/`cashflow`.

Then start the React app in a second terminal (Node 22+ recommended):

```bash
cd frontend
npm install
npm run dev                 # http://localhost:5173, proxies /api and /mcp to :8080
# API_TARGET=http://localhost:8081 npm run dev   # if the backend runs on another port
```

Open `http://localhost:5173`, click **Run agent**, then try **Demo controls** (reset, move the date forward, simulate a payment, open a customer's Fix & Pay link).

| Setting | Effect |
|---|---|
| No `.env` file | PayPal calls are simulated, and the agent uses a **scripted stand-in** (not AI). This is useful for building the UI. |
| `PAYPAL_ENABLED=true` + sandbox credentials | Real PayPal sandbox Invoicing API calls. Set `PAYPAL_ENABLED=false` in `.env` if you have no credentials yet. |
| `PAYPAL_WEBHOOK_ID` set | Webhooks are verified with PayPal and processed. Without it every webhook is rejected. |
| `CASHFLOW_LLM_API_KEY` set | The real LLM decides which tools to call. |
| `CASHFLOW_DEMO_ENABLED=false` | Turns off `/api/demo/*` (reset, time travel, simulated payments, access keys). Do this outside demos. |

LLM settings (all in `.env`):

| Variable | Default | Notes |
|---|---|---|
| `CASHFLOW_LLM_API_KEY` | empty | Empty means the scripted stand-in is used. |
| `CASHFLOW_LLM_PROVIDER` | `groq` | `groq` works with any OpenAI-compatible endpoint. Use `anthropic` for Claude. |
| `CASHFLOW_LLM_BASE_URL` | `https://api.groq.com/openai/v1` | Use `https://api.anthropic.com` with the `anthropic` provider. |
| `CASHFLOW_LLM_MODEL` | `openai/gpt-oss-120b` | Any tool-calling model id from your provider. |

### Run the tests

Tests run against a local PostgreSQL database `cashflow_test` (created by `docker compose up -d`). Then run `mvn test`. Override the connection with `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`.

### Google login

Sign-in with Google protects the dashboard and every `/api/*` endpoint. These stay public: `/api/auth/config`, `/api/auth/google`, the customer Fix & Pay portal (`/api/portal/*`) and the PayPal webhook.

| Variable | Notes |
|---|---|
| `GOOGLE_CLIENT_ID` | OAuth "Web application" client id (Google Cloud Console → APIs & Services → Credentials). Add your frontend origin (e.g. `http://localhost:5173`) as an authorized JavaScript origin. **Empty disables login**, which is the default for local dev and tests. |
| `CASHFLOW_AUTH_SESSION_SECRET` | Secret used to sign session tokens. Render generates it for you. |
| `CASHFLOW_AUTH_ALLOWED_EMAILS` | Optional comma-separated allow-list of Google emails. Empty means any verified Google account. |

Sessions last 12 hours (`cashflow.auth.session-hours`). Auth endpoints: `GET /api/auth/config`, `POST /api/auth/google` (exchange a Google ID token for a session), `GET /api/auth/me`.

### Run the tests

Tests run against a local PostgreSQL database `cashflow_test` (created by `docker compose up -d`). Then run `mvn test`. Override the connection with `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`.

### Google login

Sign-in with Google protects the dashboard and every `/api/*` endpoint. These stay public: `/api/auth/config`, `/api/auth/google`, the customer Fix & Pay portal (`/api/portal/*`) and the PayPal webhook.

| Variable | Notes |
|---|---|
| `GOOGLE_CLIENT_ID` | OAuth "Web application" client id (Google Cloud Console → APIs & Services → Credentials). Add your frontend origin (e.g. `http://localhost:5173`) as an authorized JavaScript origin. **Empty disables login**, which is the default for local dev and tests. |
| `CASHFLOW_AUTH_SESSION_SECRET` | Secret used to sign session tokens. Render generates it for you. |
| `CASHFLOW_AUTH_ALLOWED_EMAILS` | Comma-separated Google emails that become **merchants of the default organization** on first sign-in (it owns the demo data). |

Sessions last 12 hours (`cashflow.auth.session-hours`).  Auth endpoints: `GET /api/auth/config`, `POST /api/auth/google` (exchange a Google ID token for a session), `GET /api/auth/me`.

### Signing in: customer or merchant

Each **tenant** is a seller organization; every invoice belongs to one (`invoice.tenant_id`). Sign-in has two steps:

1. **Who you are**: Google (or, with `GOOGLE_CLIENT_ID` empty, a local "sign in with any email" page for development).
2. **How you continue**, chosen on a picker and switchable any time from the sidebar:
   - **Customer**: anyone. Sees *Invoices* and a read-only *Timeline* of everything billed to their verified email, by every seller, with Fix &amp; Pay and PayPal links. Merchant APIs answer 403.
   - **Merchant**: pick one organization you are a member of (`tenant_member`), or create a new one (you become its first member). Everything (dashboard, agent runs, cases, approvals, invoices, timeline, members) is that one organization's; other organizations' records answer 404. One person can belong to many organizations but works in one at a time.

The chosen role and tenant live in the session token, and membership is re-checked on every request: a removed member drops back to the picker immediately. Calls without a token while login is off (tests, curl) act as a merchant of the default organization.

The **default organization** (seeded by `schema.sql`) is the platform operator: only its merchants can reset demo data, move the business date or change autonomy rules, because those are shared across organizations.

Endpoints: `POST /api/auth/google` · `POST /api/auth/dev` (login off only) · `GET /api/auth/me` · `GET /api/auth/options` · `POST /api/auth/select` (`{role: CUSTOMER|MERCHANT|null, tenantId}`) · `POST /api/auth/tenants` (`{name}`) · `GET /api/customer/invoices` · `GET /api/customer/timeline` · `GET /api/admin/tenant` · `POST /api/admin/members` (`{email, name}`) · `DELETE /api/admin/members/{id}`.

### Deploy on Render

`render.yaml` is a Render blueprint: one Blueprint creates the backend (Docker),
a managed PostgreSQL database, and the React frontend as a static site.
Set `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID` and
`CASHFLOW_LLM_API_KEY` in the Render dashboard after creation.

### API
### API

| Endpoint | Purpose |
|---|---|
| `GET /api/auth/config`, `POST /api/auth/google`, `GET /api/auth/me` | Google login (see above) |
| `GET /api/invoices` | Invoices with paid/outstanding, PayPal link and Fix & Pay link (AG Grid) |
| `POST /api/invoices/{id}/paypal-sync` | Push an invoice to the PayPal sandbox |
| `POST /api/invoices/{id}/paypal-refresh` | Pull payments PayPal recorded on the invoice |
| `POST /api/cases/detect` | Run detection rules only |
| `GET /api/cases`, `GET /api/cases/{id}` | Case list, case file |
| `POST /api/cases/{id}/investigate` | Run the agent on one case |
| `POST /api/cases/{id}/resolve` | Owner closes an escalated case |
| `POST /api/agent/run`, `GET /api/agent/runs/{id}` | Background agent run and its progress |
| `GET /api/actions/pending`, `POST /api/actions/{id}/approve` / `reject` | Human approval |
| `GET /api/autonomy`, `POST /api/autonomy/{type}?mode=AUTO` | Earned autonomy |
| `GET /api/dashboard` | Outstanding, overdue, collected since the agent engaged, reconciled, guardrail blocks |
| `GET /api/timeline` | Events for the Bryntum timeline: invoice due dates, pending promises, agent follow-ups |
| `GET/POST /api/portal/{token}/...` | Fix & Pay page: `po`, `already-paid`, `plan/quote`, `plan/accept`, `promise`, `dispute`, `message` |
| `POST /mcp` | MCP server for the customer's AI agent (bearer access key per customer) |
| `POST /api/paypal/webhooks` | Verified PayPal webhooks: `INVOICING.INVOICE.PAID`, `INVOICING.INVOICE.UPDATED`, `CUSTOMER.DISPUTE.CREATED` |
| `/api/demo/state`, `reset`, `advance?days=3`, `payments`, `publish-to-paypal` | Demo controls |

### Connect a customer's AI agent (MCP)

The Demo controls page shows each customer's access key and a ready-made Claude Desktop config. It uses `mcp-remote` to reach the Streamable HTTP endpoint:

```json
{
  "mcpServers": {
    "acme-accounts-payable": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp", "--header", "Authorization:${AUTH_HEADER}"],
      "env": { "AUTH_HEADER": "Bearer <Acme access key>" }
    }
  }
}
```

Tools: `get_my_open_invoices`, `get_invoice_status`, `submit_purchase_order`, `report_already_paid`, `request_payment_plan`, `accept_payment_plan`, `promise_payment`, `get_payment_link`, `raise_dispute`, `send_message`. A key only reaches that customer's invoices, and no tool can change an amount.

## PayPal integration

PayPal Invoicing v2 REST API (sandbox), in `PayPalClient`:

- OAuth2 client-credentials token
- Create a draft invoice, with the PO number in `detail.reference`, send it, and store the payer link (`detail.metadata.recipient_view_url`)
- Send reminders (with the Fix & Pay link in the note)
- Read the invoice, then update it and resend (full replace with `send_to_recipient=true`): PO numbers and partial-payment plans (`configuration.partial_payment`)
- Read the invoice's `payments.transactions` to link payments (idempotent by transaction id)
- Webhooks verified with `/v1/notifications/verify-webhook-signature`, de-duplicated by event id

## AI usage

- **Model:** `openai/gpt-oss-120b` on Groq by default, called through the OpenAI-compatible chat completions API with tool calling (`OpenAiCompatibleLlmClient`). Anthropic Claude is also supported through the Messages API (`AnthropicLlmClient`). Provider and model are set with the `CASHFLOW_LLM_*` variables.
- **What the model does:** reads the case through tools, diagnoses the root cause, writes the explanation and the customer note, and chooses an action.
- **What the model cannot do:** calculate money, touch other invoices, contact a customer before checking for a payment, offer discounts, or run sensitive actions without approval.
- `ScriptedLlmClient` is a development stand-in with fixed rules, used only when no API key is set.

## Project layout

```
src/main/java/com/cashflowai
├── domain/      entities + repositories: Invoice, Payment, ArCase, AgentAction, InvoiceMessage,
│                PaymentPromise, WebhookEvent, AutonomyRule, CustomerAccount
├── detection/   DetectionRules (pure Java), PaymentMatching, BalanceCalculator, ExceptionDetectionService
├── policy/      PolicyEngine, PaymentPlanPolicy, AutonomyService, ActionType
├── agent/       AgentOrchestrator (loop), AgentRunService (background runs), AgentTools, ToolExecutor
│   └── llm/     LlmClient, LlmConfig, OpenAiCompatibleLlmClient, AnthropicLlmClient, ScriptedLlmClient
├── lifecycle/   CaseLifecycleService: payments close cases, promises, follow-ups, disputes
├── resolution/  ResolutionService: what customers (portal or their agent) can do
├── mcp/         McpController: MCP server for the customer's AI agent
├── action/      ActionService (side effects)
├── paypal/      PayPalClient, InvoiceSyncService, PaymentSyncService
├── auth/        AuthController, AuthFilter, AuthService: Google login and signed sessions
├── seed/        DemoDataService, DemoDataSeeder
├── time/        DemoClock (business date that the demo can move forward)
└── web/         REST controllers

frontend/        React + Vite. AG Grid (invoices), AG Charts (dashboard), Bryntum Calendar (timeline):
                 login, dashboard, cases, approvals, invoices, timeline, autonomy, demo, Fix & Pay page

src/main/resources
├── application.yml           default config (PostgreSQL, reads .env)
└── db/schema.sql             PostgreSQL schema (validated against the entities in SchemaValidationTest)

docker-compose.yml            local PostgreSQL (+ cashflow_test for `mvn test`)
Dockerfile, render.yaml       Render blueprint: backend + managed Postgres + static frontend
```

## Hackathon resources used

- **PayPal Invoicing v2 + webhooks** (sandbox) — see "PayPal integration" above.
- **AG Grid / AG Charts** — invoice grid on the Invoices page; aging and case-status charts on the dashboard.
- **Bryntum Calendar** (trial package `@bryntum/calendar-trial`, aliased to `@bryntum/calendar`) — Timeline page.
- **Render** — `render.yaml` blueprint deploys the whole stack.
- **APIMatic Context Plugin for PayPal** — gives your AI coding tool PayPal API context while developing; see the hackathon resources page for install. Dev-time only, no code change needed.
- **PayPal AI Toolkit / MCP** — the project exposes its own MCP server at `/mcp` for the customer's agent.

## Team TODO

- [ ] Publish the demo invoices to the sandbox (Demo controls) and confirm the sandbox buyer can pay them
- [ ] Verify the full-update PUT payload in the sandbox (PO update and partial payment, see the TODO in `PayPalClient`)
- [ ] Register the webhook in the Developer Dashboard, set `PAYPAL_WEBHOOK_ID`, and check a real `INVOICING.INVOICE.PAID` closes a case
- [ ] Switch on the real LLM and tune `AgentOrchestrator.SYSTEM_PROMPT`
- [ ] Try the MCP flow with Claude Desktop as Acme's AP assistant
- [ ] Create the Google OAuth client, add the local and deployed frontend origins, and set `GOOGLE_CLIENT_ID` (and `CASHFLOW_AUTH_ALLOWED_EMAILS` if restricting access)
- [ ] Deploy to Render (`SPRING_PROFILES_ACTIVE=postgres`, frontend as a static site with `VITE_API_BASE`, auth variables set)
- [ ] Start Postgres locally and confirm the full `mvn test` suite passes
- [ ] Demo video under 3 minutes

## License

MIT, see [LICENSE](LICENSE).

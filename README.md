# CashFlow AI: self-healing accounts receivable on PayPal

CashFlow AI is an AI agent for small businesses that invoice through PayPal. It finds invoices where money is stuck, works out **why**, takes the safest next action through PayPal, and keeps going until the invoice is resolved.

Detect → Understand → Act → Verify → Reconcile → Resolve

Built for the *Build What's Next with PayPal and AI* hackathon.

## How it works

| Layer | Responsibility |
|---|---|
| **Exception detection (Java)** | Deterministic rules decide *that* something is wrong: overdue, payment not reconciled, or partial payment. |
| **AI agent (LLM + tools)** | Decides *why* (missing PO, no response, unrecorded payment, and so on) and picks one action. It only acts through tools. |
| **Policy engine (Java)** | Every tool call is checked first. Safe actions run automatically. PO changes, refunds, discounts and write-offs need a human. |
| **Action service (Java + PayPal)** | Performs the action: PayPal reminder, PO update and resend, or reconciliation. All money math uses `BigDecimal` in Java. The AI never calculates amounts. |
| **Audit trail** | Every tool call, policy decision and approval is stored in `agent_action`. |

### Demo scenarios (seeded on startup)

| Invoice | Situation | What the agent does |
|---|---|---|
| INV-101 Acme, $4,500 | Customer says the PO number is missing | Diagnoses MISSING_PO and proposes adding PO-88421. **Waits for approval**, then updates the invoice and resends it through PayPal. |
| INV-102 Nova Labs, $1,200 | 8 days overdue, no reply | Sends a polite reminder automatically. |
| INV-103 Brightside, $800 | Paid in PayPal, books still show it open | Finds the payment and auto-reconciles it, because the amount matches exactly. **Self-healed.** |
| INV-104 Orbit, $2,500 | $2,000 received | Calculates the $500 balance in Java and reminds the customer. |
| INV-105 Lumen, $6,400 | 65 days overdue | Escalates to the finance team. |

Customer replies are simulated, and all customers are fictional.

## Run it locally

Requirements: Java 21 and Maven, or IntelliJ IDEA, which has Maven built in.

```bash
git clone https://github.com/anupama-27/paypal_hackathon.git
cd paypal_hackathon
cp .env.example .env        # fill in values (optional at first)
mvn spring-boot:run
```

It runs on `http://localhost:8080` with an in-memory H2 database and demo data.

| Setting | Effect |
|---|---|
| No `.env` file | PayPal calls are simulated, and the agent uses a **scripted stand-in** (not AI). This is useful for building the UI. |
| `PAYPAL_ENABLED=true` + sandbox credentials | Real PayPal sandbox Invoicing API calls. Set `PAYPAL_ENABLED=false` in `.env` if you have no credentials yet. |
| `CASHFLOW_LLM_API_KEY` set | The real LLM decides which tools to call. |

LLM settings (all in `.env`):

| Variable | Default | Notes |
|---|---|---|
| `CASHFLOW_LLM_API_KEY` | empty | Empty means the scripted stand-in is used. |
| `CASHFLOW_LLM_PROVIDER` | `groq` | `groq` works with any OpenAI-compatible endpoint. Use `anthropic` for Claude. |
| `CASHFLOW_LLM_BASE_URL` | `https://api.groq.com/openai/v1` | Use `https://api.anthropic.com` with the `anthropic` provider. |
| `CASHFLOW_LLM_MODEL` | `openai/gpt-oss-120b` | Any tool-calling model id from your provider. |

### Run with PostgreSQL

The `postgres` profile creates tables from `src/main/resources/db/schema.sql` on startup and seeds demo data only if the tables are empty.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DATABASE_URL=jdbc:postgresql://HOST:5432/DBNAME
export DATABASE_USERNAME=...
export DATABASE_PASSWORD=...
mvn spring-boot:run
```

### Try the API

```bash
curl -X POST localhost:8080/api/agent/run          # detect + investigate all cases
curl localhost:8080/api/dashboard                  # summary numbers
curl localhost:8080/api/cases                      # cases with root cause and explanation
curl localhost:8080/api/cases/1/actions            # audit trail for case 1
curl -X POST "localhost:8080/api/actions/3/approve?by=utsav"   # approve a pending action
curl -X POST localhost:8080/api/invoices/1/paypal-sync         # create + send INV-101 in the PayPal sandbox
```

| Endpoint | Purpose |
|---|---|
| `GET /api/invoices` | Invoices with paid and outstanding amounts (for AG Grid) |
| `POST /api/invoices/{id}/paypal-sync` | Push an invoice to the PayPal sandbox |
| `POST /api/cases/detect` | Run detection rules only |
| `POST /api/cases/{id}/investigate` | Run the agent on one case |
| `POST /api/agent/run` | Detect and investigate everything |
| `GET /api/cases/{id}/actions` | Audit trail |
| `POST /api/actions/{id}/approve` / `reject` | Human approval |
| `GET /api/dashboard` | Outstanding, overdue, recovered by AI, self-healed |
| `POST /api/paypal/webhooks` | PayPal webhook receiver (`INVOICING.INVOICE.PAID`) |

## PayPal integration

PayPal Invoicing v2 REST API (sandbox), in `PayPalClient`:

- OAuth2 client-credentials token
- Create a draft invoice, with the PO number in `detail.reference`
- Send the invoice and send reminders
- Read the invoice, then update it and resend (full replace with `send_to_recipient=true`)
- Webhook receiver for invoice paid events

## AI usage

- **Model:** `openai/gpt-oss-120b` on Groq by default, called through the OpenAI-compatible chat completions API with tool calling (`OpenAiCompatibleLlmClient`). Anthropic Claude is also supported through the Messages API (`AnthropicLlmClient`). Provider and model are set with the `CASHFLOW_LLM_*` variables.
- **What the model does:** reads the case through tools, diagnoses the root cause, writes the explanation and the customer note, and chooses an action.
- **What the model cannot do:** calculate money, touch other invoices, or run sensitive actions without approval.
- `ScriptedLlmClient` is a development stand-in with fixed rules, used only when no API key is set.

## Project layout

```
src/main/java/com/cashflowai
├── domain/      entities: Invoice, Payment, ArCase, AgentAction + repositories
├── detection/   DetectionRules (pure Java), BalanceCalculator, ExceptionDetectionService
├── policy/      PolicyEngine, ActionType
├── agent/       AgentOrchestrator (loop), AgentTools (tool specs), ToolExecutor
│   └── llm/     LlmClient, LlmConfig, OpenAiCompatibleLlmClient, AnthropicLlmClient, ScriptedLlmClient
├── action/      ActionService (side effects)
├── paypal/      PayPalClient, InvoiceSyncService
├── seed/        DemoDataSeeder
└── web/         REST controllers

src/main/resources
├── application.yml           default config (H2, reads .env)
├── application-postgres.yml  postgres profile
└── db/schema.sql             PostgreSQL schema
```

## Team TODO

- [ ] Run `PayPal sync` on the demo invoices and confirm that the sandbox buyer receives and can pay them
- [ ] Verify the full-update PUT payload in the sandbox (see the TODO in `PayPalClient`)
- [ ] Log a real `INVOICING.INVOICE.PAID` webhook and confirm the payload parsing, then add signature verification
- [ ] Switch on the real LLM and tune `AgentOrchestrator.SYSTEM_PROMPT`
- [ ] React + AG Grid dashboard (invoices, cases, approve button, before/after numbers)
- [ ] Deploy to Render (`SPRING_PROFILES_ACTIVE=postgres`)
- [ ] Demo video under 3 minutes

## License

MIT, see [LICENSE](LICENSE).

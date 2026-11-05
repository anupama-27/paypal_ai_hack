package com.cashflowai.mcp;

import com.cashflowai.domain.CustomerAccount;
import com.cashflowai.domain.CustomerAccountRepository;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.resolution.ResolutionService;
import com.cashflowai.resolution.ResolutionService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-readable invoices: a Model Context Protocol server (Streamable HTTP, JSON responses)
 * that a customer's own AI agent connects to with its access key. It can only see and act on
 * that customer's invoices, and every action goes through the same policy engine as our agent.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int REQUESTS_PER_MINUTE = 60;
    private static final EnumSet<InvoiceStatus> NOT_OPEN =
            EnumSet.of(InvoiceStatus.DRAFT, InvoiceStatus.PAID, InvoiceStatus.CANCELLED);

    private final CustomerAccountRepository accounts;
    private final InvoiceRepository invoices;
    private final ResolutionService resolution;
    private final ObjectMapper mapper;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    public McpController(CustomerAccountRepository accounts, InvoiceRepository invoices,
                         ResolutionService resolution, ObjectMapper mapper) {
        this.accounts = accounts;
        this.invoices = invoices;
        this.resolution = resolution;
        this.mapper = mapper;
    }

    /** This server does not push server-initiated messages, so there is no SSE stream. */
    @GetMapping("/mcp")
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                           @RequestBody JsonNode request) {
        Optional<CustomerAccount> customer = authenticate(auth);
        if (customer.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"cashflow-ai\"")
                    .body(error(request.path("id"), -32001, "Missing or invalid access key"));
        }
        if (!withinRateLimit(customer.get().getAccessKey())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error(request.path("id"), -32002, "Rate limit exceeded"));
        }

        String method = request.path("method").asText();
        if (!request.has("id")) {
            return ResponseEntity.accepted().build();
        }
        JsonNode id = request.get("id");
        return switch (method) {
            case "initialize" -> ResponseEntity.ok(ok(id, initialize(request.path("params"), customer.get())));
            case "ping" -> ResponseEntity.ok(ok(id, mapper.createObjectNode()));
            case "tools/list" -> ResponseEntity.ok(ok(id, toolsList()));
            case "tools/call" -> ResponseEntity.ok(ok(id, callTool(customer.get(), request.path("params"))));
            default -> ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
        };
    }

    private ObjectNode initialize(JsonNode params, CustomerAccount customer) {
        ObjectNode r = mapper.createObjectNode();
        r.put("protocolVersion", params.path("protocolVersion").asText(PROTOCOL_VERSION));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", "cashflow-ai-invoices").put("version", "0.2.0");
        r.put("instructions", "You are connected as " + customer.getName() + " to your vendor's invoices. "
                + "Use these tools to check what you owe, fix what blocks payment (missing PO, wrong details), "
                + "agree a payment plan within the vendor's limits, promise a payment date, or get the PayPal "
                + "payment link. Amounts can never be changed through this channel.");
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode tools = r.putArray("tools");
        tool(tools, "get_my_open_invoices", "List every open invoice this vendor has issued to you, with outstanding balance.");
        tool(tools, "get_invoice_status", "Full status of one invoice: amount, paid, outstanding, due date, PO, "
                + "messages and payment-plan installments.", "invoice_number", "string", "Invoice number, e.g. INV-101");
        tool(tools, "submit_purchase_order", "Add your purchase order (PO) number to an invoice. "
                        + "The vendor's system updates the PayPal invoice and re-sends it.",
                "invoice_number", "string", "Invoice number",
                "purchase_order", "string", "Your PO number");
        tool(tools, "report_already_paid", "Tell the vendor you already paid. They match it against PayPal payments.",
                "invoice_number", "string", "Invoice number",
                "payment_reference", "string", "PayPal transaction id or your payment reference");
        tool(tools, "request_payment_plan", "Ask for installments. Returns an offer, or the closest counter-offer "
                        + "inside the vendor's limits. Does not commit you.",
                "invoice_number", "string", "Invoice number",
                "installments", "integer", "Number of payments you would like",
                "spread_over_days", "integer", "Days over which to spread them");
        tool(tools, "accept_payment_plan", "Accept a payment plan with the same values you requested. "
                        + "The vendor enables partial payment on the PayPal invoice.",
                "invoice_number", "string", "Invoice number",
                "installments", "integer", "Number of payments",
                "spread_over_days", "integer", "Days over which to spread them");
        tool(tools, "promise_payment", "Commit to paying an amount by a date (no reminders until then).",
                "invoice_number", "string", "Invoice number",
                "amount", "string", "Amount you will pay, e.g. 1200.00",
                "pay_by_date", "string", "Date in YYYY-MM-DD format");
        tool(tools, "get_payment_link", "Get the PayPal link where the invoice can be paid.",
                "invoice_number", "string", "Invoice number");
        tool(tools, "raise_dispute", "Flag that something on the invoice is wrong. Reminders stop and a person follows up.",
                "invoice_number", "string", "Invoice number",
                "reason", "string", "What is wrong");
        tool(tools, "send_message", "Send a message about an invoice to the vendor's team.",
                "invoice_number", "string", "Invoice number",
                "message", "string", "Your message");
        return r;
    }

    private ObjectNode callTool(CustomerAccount customer, JsonNode params) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        log.info("MCP {} -> {} {}", customer.getEmail(), name, args);
        try {
            Object result = switch (name) {
                case "get_my_open_invoices" -> invoices.findByCustomerEmailIgnoreCase(customer.getEmail()).stream()
                        .filter(i -> !NOT_OPEN.contains(i.getStatus()))
                        .map(resolution::summary)
                        .toList();
                case "get_invoice_status" -> resolution.summary(ownInvoice(customer, args));
                case "submit_purchase_order" -> resolution.submitPurchaseOrder(ownInvoice(customer, args),
                        args.path("purchase_order").asText(), Actor.CUSTOMER_AGENT);
                case "report_already_paid" -> resolution.reportAlreadyPaid(ownInvoice(customer, args),
                        args.path("payment_reference").asText(), Actor.CUSTOMER_AGENT);
                case "request_payment_plan" -> resolution.quotePlan(ownInvoice(customer, args),
                        args.path("installments").asInt(1), args.path("spread_over_days").asInt(0));
                case "accept_payment_plan" -> resolution.acceptPlan(ownInvoice(customer, args),
                        args.path("installments").asInt(1), args.path("spread_over_days").asInt(0),
                        Actor.CUSTOMER_AGENT);
                case "promise_payment" -> resolution.promise(ownInvoice(customer, args),
                        new BigDecimal(args.path("amount").asText("0")),
                        LocalDate.parse(args.path("pay_by_date").asText()), Actor.CUSTOMER_AGENT);
                case "get_payment_link" -> resolution.paymentLink(ownInvoice(customer, args));
                case "raise_dispute" -> resolution.dispute(ownInvoice(customer, args),
                        args.path("reason").asText(), Actor.CUSTOMER_AGENT);
                case "send_message" -> resolution.message(ownInvoice(customer, args),
                        args.path("message").asText(), Actor.CUSTOMER_AGENT);
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
            boolean refused = result instanceof Result r && !r.ok();
            return toolResult(result, refused);
        } catch (RuntimeException e) {
            return toolResult(Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()), true);
        }
    }

    /** A customer key only ever reaches that customer's invoices; anything else looks like "not found". */
    private Invoice ownInvoice(CustomerAccount customer, JsonNode args) {
        String number = args.path("invoice_number").asText();
        return invoices.findByInvoiceNumber(number)
                .filter(i -> i.getCustomerEmail() != null && i.getCustomerEmail().equalsIgnoreCase(customer.getEmail()))
                .orElseThrow(() -> new IllegalArgumentException("No invoice " + number + " for your account"));
    }

    private Optional<CustomerAccount> authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return accounts.findByAccessKey(auth.substring(7).trim());
    }

    private boolean withinRateLimit(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> window = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= REQUESTS_PER_MINUTE) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void tool(ArrayNode tools, String name, String description, String... props) {
        ObjectNode t = tools.addObject();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (int i = 0; i + 2 < props.length; i += 3) {
            properties.putObject(props[i]).put("type", props[i + 1]).put("description", props[i + 2]);
            required.add(props[i]);
        }
    }

    private ObjectNode toolResult(Object value, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        String text;
        try {
            text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            text = String.valueOf(value);
        }
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    private ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null || id.isMissingNode() ? mapper.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}

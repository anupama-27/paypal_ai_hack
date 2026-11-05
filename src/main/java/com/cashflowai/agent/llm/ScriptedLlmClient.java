package com.cashflowai.agent.llm;

import com.cashflowai.agent.AgentTools;
import com.cashflowai.agent.AgentTools.ToolSpec;
import com.cashflowai.agent.llm.LlmReply.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEVELOPMENT STAND-IN, NOT AI. Plays the demo scenarios with fixed rules so the
 * whole loop (tools, policy, approvals, audit) can be built and tested without an API key.
 * Replaced by a real model when CASHFLOW_LLM_API_KEY is set. Do not demo this as the AI.
 */
public class ScriptedLlmClient implements LlmClient {

    private static final Pattern PO = Pattern.compile("PO-\\d+");
    private static final Pattern DISCOUNT = Pattern.compile("(?i)discount|\\d+\\s*%\\s*off");
    private static final Pattern PERCENT = Pattern.compile("(\\d+)\\s*%");
    private static final Set<String> TERMINAL = Set.of(AgentTools.SEND_REMINDER, AgentTools.RESEND_INVOICE,
            AgentTools.UPDATE_PURCHASE_ORDER, AgentTools.RECONCILE_PAYMENT, AgentTools.ESCALATE_TO_HUMAN);

    private final ObjectMapper mapper;

    public ScriptedLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmReply send(String systemPrompt, List<ObjectNode> messages, List<ToolSpec> tools) {
        JsonNode caseInfo = parseCaseInfo(messages.get(0));
        Map<String, JsonNode> results = toolResults(messages);
        List<ToolCall> calls = decide(caseInfo.path("case_type").asText(), results);

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        ArrayNode content = assistant.putArray("content");
        for (ToolCall c : calls) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", c.id());
            block.put("name", c.name());
            block.set("input", c.input());
        }
        String text = calls.isEmpty() ? "Done (scripted)." : "";
        if (calls.isEmpty()) {
            content.addObject().put("type", "text").put("text", text);
        }
        return new LlmReply(assistant, calls, text);
    }

    @Override
    public String name() {
        return "scripted (not AI)";
    }

    private JsonNode parseCaseInfo(ObjectNode firstMessage) {
        String text = firstMessage.path("content").asText();
        try {
            return mapper.readTree(text.substring(text.indexOf('{')));
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private List<ToolCall> decide(String caseType, Map<String, JsonNode> r) {
        List<ToolCall> calls = new ArrayList<>();
        if (TERMINAL.stream().anyMatch(r::containsKey)) {
            return calls;
        }
        if (r.containsKey(AgentTools.OFFER_DISCOUNT)) {
            calls.add(call(AgentTools.ESCALATE_TO_HUMAN, obj("reason",
                    "Customer asked for a discount; " + r.get(AgentTools.OFFER_DISCOUNT).path("reason").asText(
                            "the owner must decide"))));
            return calls;
        }
        if (!r.containsKey(AgentTools.GET_INVOICE)) {
            calls.add(call(AgentTools.GET_INVOICE, obj()));
            calls.add(call(AgentTools.CALCULATE_OUTSTANDING_BALANCE, obj()));
            calls.add(call(AgentTools.FIND_POTENTIAL_PAYMENT, obj()));
            return calls;
        }

        JsonNode invoice = r.get(AgentTools.GET_INVOICE);
        JsonNode balance = r.getOrDefault(AgentTools.CALCULATE_OUTSTANDING_BALANCE, mapper.createObjectNode());
        JsonNode candidate = r.getOrDefault(AgentTools.FIND_POTENTIAL_PAYMENT, mapper.createObjectNode())
                .path("candidates").path(0);
        long days = invoice.path("days_overdue").asLong();
        String outstanding = balance.path("outstanding").asText() + " " + balance.path("currency").asText("USD");
        String customerText = latestCustomerText(invoice);

        if (!candidate.isMissingNode()) {
            calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("PAYMENT_NOT_RECORDED", "0.96",
                    "PayPal payment " + candidate.path("paypal_payment_id").asText() + " for "
                            + candidate.path("amount").asText() + " was never matched: "
                            + candidate.path("why_it_matches").asText() + ".",
                    "Reconcile the payment instead of chasing the customer")));
            calls.add(call(AgentTools.RECONCILE_PAYMENT, obj("payment_id", candidate.path("payment_id").asText())));
            return calls;
        }

        Matcher discount = DISCOUNT.matcher(customerText);
        if (discount.find()) {
            Matcher pct = PERCENT.matcher(customerText);
            calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("DISCOUNT_REQUEST", "0.9",
                    "Customer wrote: \"" + abbreviate(customerText) + "\"", "Ask the owner about a discount")));
            calls.add(call(AgentTools.OFFER_DISCOUNT, obj("percent", pct.find() ? pct.group(1) : "unspecified",
                    "reason", "Customer requested it in their message")));
            return calls;
        }

        Matcher po = PO.matcher(customerText);
        if (po.find() && !po.group().equals(invoice.path("purchase_order").asText())) {
            calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("MISSING_PO", "0.97",
                    "Customer said they cannot process the invoice without " + po.group() + ".",
                    "Add " + po.group() + " and resend")));
            calls.add(call(AgentTools.UPDATE_PURCHASE_ORDER, obj("purchase_order", po.group())));
            return calls;
        }

        switch (caseType) {
            case "BROKEN_PROMISE" -> {
                JsonNode broken = firstWithStatus(invoice.path("promises"), "BROKEN");
                String promise = broken.path("amount").asText() + " by " + broken.path("due").asText();
                calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("BROKEN_PROMISE", "0.93",
                        "Customer committed to pay " + promise + " and no payment arrived.",
                        "Polite reminder about the missed date")));
                calls.add(call(AgentTools.SEND_REMINDER, obj("note",
                        "We had noted a payment of " + promise + " but have not received it yet. "
                                + "The remaining balance is " + outstanding + ".")));
            }
            case "PARTIAL_PAYMENT" -> {
                calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("PARTIAL_PAYMENT", "0.95",
                        "Customer paid " + balance.path("total_paid").asText() + " of "
                                + balance.path("invoice_amount").asText() + ".",
                        "Remind customer about the remaining " + outstanding)));
                calls.add(call(AgentTools.SEND_REMINDER, obj("note",
                        "Thank you for your payment. A balance of " + outstanding + " remains on this invoice.")));
            }
            case "CUSTOMER_REQUEST" -> {
                calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("UNKNOWN", "0.6",
                        "Customer wrote: \"" + abbreviate(customerText) + "\"", "Owner should reply")));
                calls.add(call(AgentTools.ESCALATE_TO_HUMAN, obj("reason", "Customer message needs a human reply")));
            }
            default -> {
                if (days > 60) {
                    calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("LONG_OVERDUE", "0.9",
                            days + " days overdue with no response.", "Escalate to finance team")));
                    calls.add(call(AgentTools.ESCALATE_TO_HUMAN, obj("reason", days + " days overdue, no customer response")));
                } else {
                    calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("NO_RESPONSE", "0.9",
                            "No matching payment, no invoice issue and no reply from the customer.",
                            "Send a polite reminder")));
                    calls.add(call(AgentTools.SEND_REMINDER, obj("note",
                            "Friendly reminder that this invoice is " + days + " days past due. Balance: "
                                    + outstanding + ".")));
                }
            }
        }
        return calls;
    }

    private static String latestCustomerText(JsonNode invoice) {
        String text = "";
        for (JsonNode m : invoice.path("conversation")) {
            if (m.path("from").asText().startsWith("CUSTOMER")) {
                text = m.path("text").asText();
            }
        }
        return text;
    }

    private static JsonNode firstWithStatus(JsonNode promises, String status) {
        for (JsonNode p : promises) {
            if (status.equals(p.path("status").asText())) {
                return p;
            }
        }
        return promises.path(0);
    }

    private static String abbreviate(String s) {
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }

    /** Latest result per tool name, pairing tool_use ids from assistant turns with tool_result blocks. */
    private Map<String, JsonNode> toolResults(List<ObjectNode> messages) {
        Map<String, String> nameById = new HashMap<>();
        Map<String, JsonNode> results = new HashMap<>();
        for (ObjectNode m : messages) {
            for (JsonNode block : m.path("content")) {
                String type = block.path("type").asText();
                if ("tool_use".equals(type)) {
                    nameById.put(block.path("id").asText(), block.path("name").asText());
                } else if ("tool_result".equals(type)) {
                    String name = nameById.get(block.path("tool_use_id").asText());
                    if (name != null) {
                        try {
                            results.put(name, mapper.readTree(block.path("content").asText()));
                        } catch (Exception e) {
                            results.put(name, mapper.createObjectNode());
                        }
                    }
                }
            }
        }
        return results;
    }

    private ToolCall call(String name, ObjectNode input) {
        return new ToolCall("scripted_" + UUID.randomUUID(), name, input);
    }

    private ObjectNode obj(String... kv) {
        ObjectNode n = mapper.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    private ObjectNode diagnosis(String rootCause, String confidence, String explanation, String action) {
        return obj("root_cause", rootCause, "confidence", confidence,
                "explanation", explanation, "recommended_action", action);
    }
}

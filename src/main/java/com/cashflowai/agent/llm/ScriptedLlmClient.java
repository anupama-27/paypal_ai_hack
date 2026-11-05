package com.cashflowai.agent.llm;

import com.cashflowai.agent.AgentTools;
import com.cashflowai.agent.AgentTools.ToolSpec;
import com.cashflowai.agent.llm.LlmReply.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
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

    private final ObjectMapper mapper;

    public ScriptedLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LlmReply send(String systemPrompt, List<ObjectNode> messages, List<ToolSpec> tools) {
        JsonNode caseInfo = parseCaseInfo(messages.get(0));
        int step = (int) messages.stream().filter(m -> "assistant".equals(m.path("role").asText())).count();
        JsonNode last = lastToolResult(messages);
        String caseType = caseInfo.path("case_type").asText();

        List<ToolCall> calls = new ArrayList<>();
        switch (caseType) {
            case "RECONCILIATION_MISMATCH" -> {
                if (step == 0) {
                    calls.add(call(AgentTools.FIND_POTENTIAL_PAYMENT, obj()));
                } else if (step == 1) {
                    JsonNode candidate = last.path("candidates").path(0);
                    String paymentId = candidate.path("payment_id").asText();
                    calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("PAYMENT_NOT_RECORDED", "0.97",
                            "PayPal payment " + candidate.path("paypal_payment_id").asText() + " for "
                                    + candidate.path("amount").asText() + " references this invoice but was never matched.",
                            "Reconcile payment " + paymentId)));
                    calls.add(call(AgentTools.RECONCILE_PAYMENT, obj("payment_id", paymentId)));
                }
            }
            case "PARTIAL_PAYMENT" -> {
                if (step == 0) {
                    calls.add(call(AgentTools.CALCULATE_OUTSTANDING_BALANCE, obj()));
                } else if (step == 1) {
                    String outstanding = last.path("outstanding").asText();
                    calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("PARTIAL_PAYMENT", "0.95",
                            "Customer paid " + last.path("total_paid").asText() + " of "
                                    + last.path("invoice_amount").asText() + ".",
                            "Remind customer about the remaining " + outstanding)));
                    calls.add(call(AgentTools.SEND_REMINDER, obj("note",
                            "Thank you for your payment. A balance of " + outstanding + " "
                                    + last.path("currency").asText() + " remains on this invoice.")));
                }
            }
            default -> { // OVERDUE_INVOICE
                if (step == 0) {
                    calls.add(call(AgentTools.GET_INVOICE, obj()));
                } else if (step == 1) {
                    String message = last.path("latest_customer_message").asText();
                    long days = last.path("days_overdue").asLong();
                    Matcher po = PO.matcher(message);
                    if (po.find()) {
                        calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("MISSING_PO", "0.97",
                                "Customer said they cannot process the invoice without " + po.group() + ".",
                                "Add " + po.group() + " and resend")));
                        calls.add(call(AgentTools.UPDATE_PURCHASE_ORDER, obj("purchase_order", po.group())));
                    } else if (days > 60) {
                        calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("LONG_OVERDUE", "0.9",
                                days + " days overdue with no response.", "Escalate to finance team")));
                        calls.add(call(AgentTools.ESCALATE_TO_HUMAN, obj("reason", days + " days overdue, no customer response")));
                    } else {
                        calls.add(call(AgentTools.RECORD_DIAGNOSIS, diagnosis("NO_RESPONSE", "0.9",
                                "No dispute, no invoice issue and no reply from the customer.", "Send a polite reminder")));
                        calls.add(call(AgentTools.SEND_REMINDER, obj("note",
                                "Friendly reminder that this invoice is " + days + " days past due.")));
                    }
                }
            }
        }

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

    private JsonNode lastToolResult(List<ObjectNode> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            for (JsonNode block : messages.get(i).path("content")) {
                if ("tool_result".equals(block.path("type").asText())) {
                    try {
                        // first tool_result in the latest user message
                        return mapper.readTree(messages.get(i).path("content").path(0).path("content").asText());
                    } catch (Exception e) {
                        return mapper.createObjectNode();
                    }
                }
            }
        }
        return mapper.createObjectNode();
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

package com.cashflowai.agent;

import com.cashflowai.policy.ActionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools the LLM is allowed to call. Each agent run works on ONE case,
 * so tools never take an invoice id: the agent cannot touch other invoices.
 */
@Component
public class AgentTools {

    public record ToolSpec(String name, String description, ObjectNode inputSchema, ActionType actionType) {
    }

    public static final String GET_INVOICE = "get_invoice";
    public static final String GET_INVOICE_PAYMENTS = "get_invoice_payments";
    public static final String CALCULATE_OUTSTANDING_BALANCE = "calculate_outstanding_balance";
    public static final String FIND_POTENTIAL_PAYMENT = "find_potential_payment";
    public static final String SEND_REMINDER = "send_reminder";
    public static final String RESEND_INVOICE = "resend_invoice";
    public static final String UPDATE_PURCHASE_ORDER = "update_purchase_order";
    public static final String RECONCILE_PAYMENT = "reconcile_payment";
    public static final String ESCALATE_TO_HUMAN = "escalate_to_human";
    public static final String RECORD_DIAGNOSIS = "record_diagnosis";

    private final List<ToolSpec> specs;
    private final Map<String, ToolSpec> byName;

    public AgentTools(ObjectMapper mapper) {
        Schema s = new Schema(mapper);
        this.specs = List.of(
                new ToolSpec(GET_INVOICE,
                        "Get the invoice under investigation: number, customer, amount, due date, status, "
                                + "purchase order, days overdue and the latest customer message.",
                        s.object(), ActionType.READ_INVOICE),
                new ToolSpec(GET_INVOICE_PAYMENTS,
                        "List payments already matched to this invoice.",
                        s.object(), ActionType.CHECK_PAYMENT),
                new ToolSpec(CALCULATE_OUTSTANDING_BALANCE,
                        "Get invoice amount, total paid and outstanding balance. Always use this; never calculate money yourself.",
                        s.object(), ActionType.CALCULATE_BALANCE),
                new ToolSpec(FIND_POTENTIAL_PAYMENT,
                        "Find PayPal payments that reference this invoice but are not yet matched to it.",
                        s.object(), ActionType.FIND_POTENTIAL_PAYMENT),
                new ToolSpec(SEND_REMINDER,
                        "Send the customer a payment reminder through PayPal with a short, polite note.",
                        s.object("note", "Reminder text for the customer"), ActionType.SEND_REMINDER),
                new ToolSpec(RESEND_INVOICE,
                        "Resend the unchanged invoice to the customer.",
                        s.object(), ActionType.RESEND_INVOICE),
                new ToolSpec(UPDATE_PURCHASE_ORDER,
                        "Set the purchase order (PO) number on the invoice and resend it. Requires human approval.",
                        s.object("purchase_order", "PO number exactly as the customer provided it"),
                        ActionType.UPDATE_REFERENCE),
                new ToolSpec(RECONCILE_PAYMENT,
                        "Match an unmatched PayPal payment to this invoice. Use the payment_id from find_potential_payment.",
                        s.object("payment_id", "Internal payment id"), ActionType.RECONCILE_PAYMENT),
                new ToolSpec(ESCALATE_TO_HUMAN,
                        "Hand the case to the finance team when it cannot be resolved safely.",
                        s.object("reason", "Why a human is needed"), ActionType.ESCALATE_TO_HUMAN),
                new ToolSpec(RECORD_DIAGNOSIS,
                        "Record the root cause before finishing. root_cause must be one of: MISSING_PO, NO_RESPONSE, "
                                + "PAYMENT_NOT_RECORDED, PARTIAL_PAYMENT, LONG_OVERDUE, UNKNOWN.",
                        s.object("root_cause", "Root cause enum value",
                                "confidence", "Number between 0 and 1",
                                "explanation", "One or two sentences of evidence for the finance team",
                                "recommended_action", "Short description of the next action"),
                        ActionType.RECORD_DIAGNOSIS)
        );
        this.byName = specs.stream().collect(java.util.stream.Collectors.toMap(ToolSpec::name, t -> t));
    }

    public List<ToolSpec> all() {
        return specs;
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Builds simple JSON Schemas with string properties (all required). */
    private record Schema(ObjectMapper mapper) {
        ObjectNode object(String... nameDescriptionPairs) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            var required = schema.putArray("required");
            for (int i = 0; i + 1 < nameDescriptionPairs.length; i += 2) {
                ObjectNode p = props.putObject(nameDescriptionPairs[i]);
                p.put("type", "string");
                p.put("description", nameDescriptionPairs[i + 1]);
                required.add(nameDescriptionPairs[i]);
            }
            return schema;
        }
    }
}

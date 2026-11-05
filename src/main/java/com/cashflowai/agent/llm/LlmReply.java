package com.cashflowai.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * @param assistantMessage the assistant message to append to the conversation as-is
 * @param toolCalls        tools the model wants to call (empty = it is done)
 * @param text             any plain text the model wrote
 */
public record LlmReply(ObjectNode assistantMessage, List<ToolCall> toolCalls, String text) {

    public record ToolCall(String id, String name, JsonNode input) {
    }
}

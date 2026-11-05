package com.cashflowai.agent.llm;

import com.cashflowai.agent.AgentTools.ToolSpec;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One turn of a tool-calling conversation. Messages use the Anthropic Messages API shape
 * ({"role": ..., "content": ...}); another provider would need an adapter.
 */
public interface LlmClient {

    LlmReply send(String systemPrompt, List<ObjectNode> messages, List<ToolSpec> tools);

    /** Name shown in logs and the dashboard, e.g. "anthropic:claude-haiku-5-5" or "scripted". */
    String name();
}

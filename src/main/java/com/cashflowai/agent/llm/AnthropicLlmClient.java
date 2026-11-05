package com.cashflowai.agent.llm;

import com.cashflowai.agent.AgentTools.ToolSpec;
import com.cashflowai.agent.llm.LlmReply.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/** Calls the Anthropic Messages API with tool definitions (plain REST, no SDK). */
public class AnthropicLlmClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AnthropicLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Override
    public LlmReply send(String systemPrompt, List<ObjectNode> messages, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", 1024);
        body.put("system", systemPrompt);

        ArrayNode toolArray = body.putArray("tools");
        for (ToolSpec t : tools) {
            ObjectNode tool = toolArray.addObject();
            tool.put("name", t.name());
            tool.put("description", t.description());
            tool.set("input_schema", t.inputSchema());
        }

        ArrayNode msgArray = body.putArray("messages");
        messages.forEach(msgArray::add);

        JsonNode response = http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            switch (block.path("type").asText()) {
                case "tool_use" -> calls.add(new ToolCall(
                        block.path("id").asText(), block.path("name").asText(), block.path("input")));
                case "text" -> text.append(block.path("text").asText());
                default -> { }
            }
        }

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        assistant.set("content", response.path("content"));
        return new LlmReply(assistant, calls, text.toString());
    }

    @Override
    public String name() {
        return "anthropic:" + props.model();
    }
}

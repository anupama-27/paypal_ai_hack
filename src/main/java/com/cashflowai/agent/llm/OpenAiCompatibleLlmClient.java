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

/**
 * Calls any OpenAI-style /chat/completions endpoint (Groq, Hugging Face router, OpenRouter, Ollama).
 * The orchestrator keeps Anthropic-shaped messages; this class converts them on the way in and out.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
    }

    @Override
    public LlmReply send(String systemPrompt, List<ObjectNode> messages, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", 1024);

        ArrayNode msgArray = body.putArray("messages");
        msgArray.addObject().put("role", "system").put("content", systemPrompt);
        for (ObjectNode m : messages) {
            convert(m, msgArray);
        }

        ArrayNode toolArray = body.putArray("tools");
        for (ToolSpec t : tools) {
            ObjectNode fn = toolArray.addObject().put("type", "function").putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.set("parameters", t.inputSchema());
        }

        JsonNode response = http.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = response.path("choices").path(0).path("message");
        String text = message.path("content").asText("");

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        ArrayNode content = assistant.putArray("content");
        if (!text.isBlank()) {
            content.addObject().put("type", "text").put("text", text);
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : message.path("tool_calls")) {
            String id = tc.path("id").asText();
            String name = tc.path("function").path("name").asText();
            JsonNode input = parseArguments(tc.path("function").path("arguments").asText("{}"));
            calls.add(new ToolCall(id, name, input));
            ObjectNode use = content.addObject();
            use.put("type", "tool_use");
            use.put("id", id);
            use.put("name", name);
            use.set("input", input);
        }
        return new LlmReply(assistant, calls, text);
    }

    private JsonNode parseArguments(String json) {
        try {
            return json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** Anthropic-shaped message -> one or more OpenAI-shaped messages. */
    private void convert(ObjectNode m, ArrayNode out) {
        String role = m.path("role").asText();
        JsonNode content = m.path("content");

        if (content.isTextual()) {
            out.addObject().put("role", role).put("content", content.asText());
            return;
        }

        if ("assistant".equals(role)) {
            StringBuilder text = new StringBuilder();
            ObjectNode msg = out.addObject();
            msg.put("role", "assistant");
            ArrayNode toolCalls = mapper.createArrayNode();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    text.append(block.path("text").asText());
                } else if ("tool_use".equals(type)) {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", block.path("id").asText());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", block.path("name").asText());
                    fn.put("arguments", block.path("input").toString());
                }
            }
            if (text.length() > 0) {
                msg.put("content", text.toString());
            } else {
                msg.putNull("content");
            }
            if (!toolCalls.isEmpty()) {
                msg.set("tool_calls", toolCalls);
            }
            return;
        }

        for (JsonNode block : content) {
            if ("tool_result".equals(block.path("type").asText())) {
                ObjectNode tool = out.addObject();
                tool.put("role", "tool");
                tool.put("tool_call_id", block.path("tool_use_id").asText());
                tool.put("content", block.path("content").asText());
            }
        }
    }

    @Override
    public String name() {
        return props.provider() + ":" + props.model();
    }
}

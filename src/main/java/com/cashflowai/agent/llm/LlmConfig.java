package com.cashflowai.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** Real model when CASHFLOW_LLM_API_KEY is set, otherwise the scripted stand-in. */
    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        if (props.hasApiKey()) {
            log.info("Agent brain: {} model {}", props.provider(), props.model());
            return props.isAnthropic()
                    ? new AnthropicLlmClient(props, mapper)
                    : new OpenAiCompatibleLlmClient(props, mapper);
        }
        log.warn("Agent brain: SCRIPTED stand-in (no CASHFLOW_LLM_API_KEY). Set the key to use the real model.");
        return new ScriptedLlmClient(mapper);
    }
}

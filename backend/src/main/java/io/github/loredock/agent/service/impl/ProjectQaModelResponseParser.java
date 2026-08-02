package io.github.loredock.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.config.AgentRuntimeLimits;
import io.github.loredock.agent.exception.AgentExecutionException;
import io.github.loredock.agent.model.enums.AgentErrorCode;
import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 解析锁定版本 ReactAgent 返回的 AssistantMessage，并执行基础结构与长度校验。 */
final class ProjectQaModelResponseParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectQaModelResponseParser.class);

    private final ObjectMapper objectMapper;

    ProjectQaModelResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectQaModelResult parse(String json, AgentRuntimeLimits limits) {
        try {
            JsonNode root = objectMapper.readTree(jsonObject(json));
            AgentResultType resultType = AgentResultType.valueOf(required(root, "resultType"));
            String basisValue = root.path("answerBasis").asText(null);
            AnswerBasis basis = basisValue == null || basisValue.isBlank()
                    ? null : AnswerBasis.valueOf(basisValue);
            String text = required(root, "text").strip();
            if (text.codePointCount(0, text.length()) > limits.maxAnswerCharacters()) {
                throw new IllegalArgumentException("model answer exceeds limit");
            }
            AgentRefusalReason reason = root.path("refusalReason").isNull()
                    || root.path("refusalReason").isMissingNode()
                    ? null : AgentRefusalReason.valueOf(root.path("refusalReason").asText());
            List<Long> citations = new ArrayList<>();
            JsonNode values = root.path("citations");
            if (!values.isArray() || values.size() > 20) {
                throw new IllegalArgumentException("citations invalid");
            }
            values.forEach(value -> citations.add(Long.valueOf(value.asText())));
            return new ProjectQaModelResult(resultType, basis, text, reason, citations);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("agent_model_response_invalid responseLength={} reason={}",
                    json == null ? 0 : json.length(), exception.getClass().getSimpleName());
            throw new AgentExecutionException(AgentErrorCode.AGENT_MODEL_RESPONSE_INVALID);
        }
    }

    private String jsonObject(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("model response is blank");
        }
        String stripped = value.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("model response does not contain JSON object");
        }
        return stripped.substring(start, end + 1);
    }

    private String required(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        return value;
    }
}

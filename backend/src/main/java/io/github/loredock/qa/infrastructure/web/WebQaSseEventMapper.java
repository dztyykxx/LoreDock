package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.agent.application.AgentEventSnapshot;
import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentResultType;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把 Agent 已提交事件白名单映射为 SSE v1，禁止直接序列化原始 payload。 */
final class WebQaSseEventMapper {
    private static final String VERSION = "v1";
    private static final Set<String> TOOLS = Set.of("knowledge_search", "code_search", "code_snippet_read");
    private static final Pattern TOOL_STARTED = Pattern.compile("^([a-z_]+)#([1-9][0-9]*)$");
    private static final Pattern TOOL_COUNT = Pattern.compile("^([a-z_]+) count=([0-9]+)$");

    private WebQaSseEventMapper() {
    }

    /** @return 有限公开事件 */
    static WebQaSsePublicEvent toPublic(AgentEventSnapshot event) {
        if (event == null || event.sequence() < 1 || event.type() == null || event.createdAt() == null) {
            throw new IllegalArgumentException("Agent event is incomplete");
        }
        return switch (event.type()) {
            case RUN_ACCEPTED -> event(event, "run.accepted", "ACCEPTED", null, null, null, null, null);
            case RUN_STARTED -> event(event, "run.started", "PREPARING", null, null, null, null, null);
            case SKILL_PINNED -> event(event, "skill.pinned", "PREPARING", null, null, null, null, null);
            case MODEL_STARTED -> event(event, "model.started", "GENERATING", null, null, null, null, null);
            case TOOL_STARTED -> event(event, "tool.started", "RETRIEVING",
                    startedTool(event.payload()), null, null, null, null);
            case TOOL_COMPLETED -> {
                ToolCount parsed = toolCount(event.payload());
                yield event(event, "tool.completed", "RETRIEVING",
                        parsed.tool(), parsed.count(), null, null, null);
            }
            case SOURCE_FOUND -> {
                ToolCount parsed = toolCount(event.payload());
                yield event(event, "source.found", "RETRIEVING",
                        parsed.tool(), parsed.count(), null, null, null);
            }
            case ANSWER_DELTA -> event(event, "answer.delta", "COMPOSING",
                    null, null, requiredText(event.payload()), null, null);
            case REFUSAL -> event(event, "answer.refusal", "REFUSED",
                    null, null, requiredText(event.payload()), AgentResultType.REFUSAL, null);
            case RUN_COMPLETED -> event(event, "run.completed", "COMPLETED",
                    null, null, null, resultType(event.payload()), null);
            case RUN_FAILED -> event(event, "run.failed", "FAILED",
                    null, null, null, null, errorCode(event.payload()));
            case RUN_TERMINATED -> event(event, "run.terminated", "TERMINATED",
                    null, null, null, null, errorCode(event.payload()));
        };
    }

    private static WebQaSsePublicEvent event(
            AgentEventSnapshot source,
            String name,
            String phase,
            String tool,
            Integer count,
            String textDelta,
            AgentResultType resultType,
            AgentErrorCode errorCode
    ) {
        return new WebQaSsePublicEvent(name, new WebQaSseEventV1(
                VERSION, source.sequence(), source.createdAt(), phase, tool, count,
                textDelta, resultType, errorCode));
    }

    private static String startedTool(String payload) {
        Matcher matcher = TOOL_STARTED.matcher(requiredText(payload));
        if (!matcher.matches() || !TOOLS.contains(matcher.group(1))) {
            throw new IllegalArgumentException("Agent tool start payload is invalid");
        }
        return matcher.group(1);
    }

    private static ToolCount toolCount(String payload) {
        Matcher matcher = TOOL_COUNT.matcher(requiredText(payload));
        if (!matcher.matches() || !TOOLS.contains(matcher.group(1))) {
            throw new IllegalArgumentException("Agent tool count payload is invalid");
        }
        try {
            int count = Integer.parseInt(matcher.group(2));
            if (count > 100) {
                throw new IllegalArgumentException("Agent tool count exceeds public bound");
            }
            return new ToolCount(matcher.group(1), count);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Agent tool count payload is invalid", exception);
        }
    }

    private static AgentResultType resultType(String payload) {
        try {
            return AgentResultType.valueOf(requiredText(payload));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Agent result payload is invalid", exception);
        }
    }

    private static AgentErrorCode errorCode(String payload) {
        try {
            return AgentErrorCode.valueOf(requiredText(payload));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Agent error payload is invalid", exception);
        }
    }

    private static String requiredText(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Agent event payload is blank");
        }
        return payload;
    }

    private record ToolCount(String tool, int count) {
    }
}

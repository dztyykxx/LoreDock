package io.github.loredock.qa.converter;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.model.snapshot.WebQaSseEventV1;
import io.github.loredock.qa.model.snapshot.WebQaSsePublicEvent;

/** 把 Agent 已提交事件白名单映射为 SSE v1，禁止直接序列化原始 payload。 */
public final class WebQaSseEventMapper {
    private static final String VERSION = "v1";

    private WebQaSseEventMapper() {
    }

    /** @return 有限公开事件 */
    public static WebQaSsePublicEvent toPublic(AgentEvent event) {
        if (event == null || event.sequence() < 1 || event.type() == null || event.createdAt() == null) {
            throw new IllegalArgumentException("Agent event is incomplete");
        }
        requireSafe(event);
        return switch (event.type()) {
            case RUN_ACCEPTED -> event(event, "run.accepted", "ACCEPTED", null, null, null, null, null);
            case RUN_STARTED -> event(event, "run.started", "PREPARING", null, null, null, null, null);
            case MODEL_STARTED, MODEL_STAGE -> event(event, "model.started");
            case AGENT_STAGE -> event(event, "agent.stage");
            case TOOL_STARTED -> event(event, "tool.started");
            case TOOL_COMPLETED -> event(event, "tool.completed");
            case SOURCE_FOUND, SOURCE_DISCOVERED -> event(event, "source.found");
            case CITATION_VALIDATION -> event(event, "citation.validation");
            case PUBLIC_DECISION_SUMMARY -> event(event, "decision.summary");
            case ANSWER_DELTA -> event(event, "answer.delta");
            case RUN_COMPLETED -> event(event, "run.completed", "COMPLETED",
                    null, null, null, event.payload().resultType(), null);
            case RUN_FAILED -> event(event, "run.failed", "FAILED",
                    null, null, null, null, event.payload().errorCode());
            case RUN_TERMINATED -> event(event, "run.terminated", "TERMINATED",
                    null, null, null, null, event.payload().errorCode());
        };
    }

    /** 类型化事件进入 REST/SSE 前的最后一道白名单检查。 */
    public static void requireSafe(AgentEvent event) {
        if (event.subjectType() == null || event.payload() == null || event.payload().sources().size() > 20) {
            throw new IllegalArgumentException("Agent event payload is incomplete");
        }
        AgentEvent.Payload payload = event.payload();
        requireSafeText(payload.phase(), 64);
        requireSafeText(payload.name(), 64);
        requireSafeText(payload.purpose(), 200);
        requireSafeText(payload.parameterSummary(), 300);
        requireSafeText(payload.resultSummary(), 500);
        requireSafeText(payload.summary(), 500);
        requireSafeText(payload.textDelta(), 1000);
        if (payload.count() != null && (payload.count() < 0 || payload.count() > 100)) {
            throw new IllegalArgumentException("Agent event count invalid");
        }
        payload.sources().forEach(source -> {
            requireSafeText(source.title(), 200);
            requireSafeText(source.scopeType(), 32);
            requireSafeText(source.sourceType(), 32);
            requireSafeText(source.relevance(), 64);
        });
    }

    private static void requireSafeText(String value, int maximumCodePoints) {
        if (value == null) {
            return;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (value.codePointCount(0, value.length()) > maximumCodePoints
                || lower.contains("/srv/") || lower.contains("token=")
                || lower.contains("password=") || lower.contains("objectkey=")) {
            throw new IllegalArgumentException("Agent event text is unsafe");
        }
    }

    private static WebQaSsePublicEvent event(AgentEvent source, String name) {
        AgentEvent.Payload payload = source.payload();
        return event(source, name, payload.phase(), payload.name(), payload.count(), payload.textDelta(),
                payload.resultType(), payload.errorCode());
    }

    private static WebQaSsePublicEvent event(
            AgentEvent source,
            String name,
            String phase,
            String tool,
            Integer count,
            String textDelta,
            AgentRun.ResultType resultType,
            AgentRun.ErrorCode errorCode
    ) {
        return new WebQaSsePublicEvent(name, new WebQaSseEventV1(
                VERSION, source.sequence(), source.createdAt(), source.type(), source.subjectType(),
                phase, tool, source.payload().purpose(), source.payload().parameterSummary(),
                source.payload().resultSummary(), count, source.payload().durationMillis(), source.payload().status(),
                source.payload().sources(), source.payload().summary(), source.payload().modelGenerated(),
                source.payload().truncated(),
                textDelta, resultType, errorCode));
    }

}

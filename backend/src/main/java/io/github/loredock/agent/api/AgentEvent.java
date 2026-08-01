package io.github.loredock.agent.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 不包含隐藏思维链的已提交公开事件。
 *
 * @param eventId 事件标识
 * @param runId 运行标识
 * @param sequence 运行内连续序号
 * @param type 公开事件类型
 * @param subjectType 事件事实主体
 * @param payload 使用字段白名单和数量上限的安全载荷
 * @param createdAt 创建时间
 */
public record AgentEvent(
        Long eventId,
        Long runId,
        long sequence,
        Type type,
        SubjectType subjectType,
        Payload payload,
        Instant createdAt
) {
    private static final Set<String> LEGACY_TOOLS = Set.of("knowledge_search", "code_search", "code_snippet_read");
    /** 公开运行事件类型。 */
    public enum Type {
        RUN_ACCEPTED,
        RUN_STARTED,
        MODEL_STARTED,
        SOURCE_FOUND,
        AGENT_STAGE,
        MODEL_STAGE,
        TOOL_STARTED,
        TOOL_COMPLETED,
        SOURCE_DISCOVERED,
        CITATION_VALIDATION,
        PUBLIC_DECISION_SUMMARY,
        ANSWER_DELTA,
        RUN_COMPLETED,
        RUN_FAILED,
        RUN_TERMINATED
    }

    /** 读取历史 v1 事件时只把原有有限字符串映射到类型化白名单，不透传任意字段。 */
    public AgentEvent(
            Long eventId,
            Long runId,
            long sequence,
            Type type,
            String legacyPayload,
            Instant createdAt
    ) {
        this(eventId, runId, sequence, type, legacySubject(type), legacyPayload(type, legacyPayload), createdAt);
    }

    /** 公开事实的执行主体；不表示新的授权边界。 */
    public enum SubjectType { AGENT, MODEL, TOOL, VALIDATOR }

    /**
     * 公开来源元数据；不包含证据正文、对象键、内部 generation 或服务器路径。
     *
     * @param documentId 知识文档标识
     * @param title 文档标题
     * @param scopeType 通用或项目范围
     * @param sourceType 公开来源类型
     * @param updatedAt 来源更新时间
     * @param relevance 公开相关性摘要
     * @param cited 是否进入最终引用
     * @param truncated 是否发生安全裁剪
     */
    public record Source(
            Long documentId,
            String title,
            String scopeType,
            String sourceType,
            Instant updatedAt,
            String relevance,
            boolean cited,
            boolean truncated
    ) {
    }

    /**
     * 类型化公开载荷。未使用字段为空；所有文本均由服务端生成或裁剪，不接收原始 Tool JSON。
     *
     * @param phase 服务端阶段
     * @param name Agent、模型或注册 Tool 名称
     * @param purpose 用户可理解的 Tool 用途
     * @param parameterSummary 脱敏参数摘要
     * @param resultSummary 脱敏结果摘要
     * @param count 结果数量
     * @param durationMillis 服务端测得耗时
     * @param status 服务端事实状态
     * @param sources 有界来源元数据
     * @param summary 明确标记的公开决策摘要
     * @param textDelta 已通过公开输出策略的文本增量
     * @param resultType 可信终态类型
     * @param errorCode 稳定失败语义
     * @param modelGenerated 摘要是否由模型生成
     * @param truncated 载荷是否发生裁剪
     */
    public record Payload(
            String phase,
            String name,
            String purpose,
            String parameterSummary,
            String resultSummary,
            Integer count,
            Long durationMillis,
            String status,
            List<Source> sources,
            String summary,
            String textDelta,
            AgentRun.ResultType resultType,
            AgentRun.ErrorCode errorCode,
            boolean modelGenerated,
            boolean truncated
    ) {
        public Payload {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    private static SubjectType legacySubject(Type type) {
        return switch (type) {
            case MODEL_STARTED, MODEL_STAGE -> SubjectType.MODEL;
            case SOURCE_FOUND, SOURCE_DISCOVERED, TOOL_STARTED, TOOL_COMPLETED -> SubjectType.TOOL;
            case CITATION_VALIDATION -> SubjectType.VALIDATOR;
            default -> SubjectType.AGENT;
        };
    }

    private static Payload legacyPayload(Type type, String value) {
        String safe = value == null ? "" : value;
        return switch (type) {
            case RUN_ACCEPTED -> payload("ACCEPTED", null, null, null, "ACCEPTED", null, null);
            case RUN_STARTED -> payload("PREPARING", null, null, null, "RUNNING", null, null);
            case MODEL_STARTED -> payload("GENERATING", null, null, null, "STARTED", null, null);
            case SOURCE_FOUND -> {
                String[] parts = safe.split(" count=", 2);
                if (parts.length != 2 || !LEGACY_TOOLS.contains(parts[0]) || !parts[1].matches("[0-9]+")) {
                    throw new IllegalArgumentException("legacy source event payload invalid");
                }
                Integer count = Integer.valueOf(parts[1]);
                if (count > 100) {
                    throw new IllegalArgumentException("legacy source event count exceeds public bound");
                }
                yield payload("RETRIEVING", parts[0], count, null, "COMPLETED", null, null);
            }
            case RUN_COMPLETED -> payload("COMPLETED", null, null, null, "COMPLETED",
                    requiredEnum(AgentRun.ResultType.class, safe), null);
            case RUN_FAILED -> payload("FAILED", null, null, null, "FAILED", null,
                    requiredEnum(AgentRun.ErrorCode.class, safe));
            case RUN_TERMINATED -> payload("TERMINATED", null, null, null, "TERMINATED", null,
                    requiredEnum(AgentRun.ErrorCode.class, safe));
            default -> payload(null, null, null, safe, null, null, null);
        };
    }

    private static Payload payload(
            String phase,
            String name,
            Integer count,
            String summary,
            String status,
            AgentRun.ResultType resultType,
            AgentRun.ErrorCode errorCode
    ) {
        return new Payload(phase, name, null, null, null, count, null, status,
                List.of(), summary, null, resultType, errorCode, false, false);
    }

    private static <T extends Enum<T>> T requiredEnum(Class<T> type, String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("legacy terminal event payload blank");
            }
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("legacy terminal event payload invalid", exception);
        }
    }
}

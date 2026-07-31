package io.github.loredock.qa.converter;

import io.github.loredock.qa.api.QaQuestion;

/**
 * 把内部稳定错误码映射为可直接展示给用户的安全说明，不暴露异常原文、模型配置或服务器信息。
 */
public final class WebQaFailureMessageMapper {

    private static final String GENERIC_FAILURE = "本次运行未形成可信回答，请使用新运行重试。";

    private WebQaFailureMessageMapper() {
    }

    /**
     * @param status 运行终态
     * @param errorCode 稳定错误码
     * @return 仅失败或终止状态返回用户可读说明，正常回答与业务拒答返回 {@code null}
     */
    public static String from(QaQuestion.Status status, QaQuestion.ErrorCode errorCode) {
        if (status != QaQuestion.Status.FAILED && status != QaQuestion.Status.TERMINATED) {
            return null;
        }
        if (errorCode == null) {
            return GENERIC_FAILURE;
        }
        return switch (errorCode) {
            case AGENT_RUN_IDEMPOTENCY_CONFLICT -> "本次请求与已有运行冲突，请刷新页面后重新提交。";
            case AGENT_SKILL_UNAVAILABLE -> "回答规则当前不可用，请稍后重试或联系管理员。";
            case AGENT_DISABLED -> "项目问答能力当前已停用，请联系管理员。";
            case AGENT_RUNTIME_UNAVAILABLE -> "回答运行环境当前不可用，请稍后重试。";
            case AGENT_RUNTIME_BUSY -> "回答运行环境当前繁忙，请稍后重试。";
            case AGENT_MODEL_UNAVAILABLE -> "回答模型当前不可用，请稍后重试。";
            case AGENT_MODEL_RESPONSE_INVALID -> "回答模型未返回可验证结果，请使用新运行重试。";
            case AGENT_TOOL_NOT_ALLOWED -> "运行请求了未授权工具，未形成可信回答。";
            case AGENT_TOOL_SCOPE_VIOLATION -> "检索超出本次项目或分支范围，未形成可信回答。";
            case AGENT_EVIDENCE_VERSION_CHANGED -> "问答期间知识或代码版本发生变化，请使用新运行重试。";
            case AGENT_STEP_LIMIT_EXCEEDED ->
                    "本次检索已达到运行上限，尚未形成可信回答。请缩小问题范围或使用新运行重试。";
            case AGENT_MODEL_CALL_LIMIT_EXCEEDED ->
                    "本次回答已达到模型调用上限，尚未形成可信回答。请缩小问题范围或使用新运行重试。";
            case AGENT_RUN_TIMEOUT -> "本次运行超时，尚未形成可信回答。请缩小问题范围或稍后重试。";
            case AGENT_RUN_INTERRUPTED -> "本次运行已中断，未形成可信回答。请使用新运行重试。";
            case AGENT_CITATION_INVALID -> "回答引用未通过校验，系统已阻止展示不可信结果。";
            case AGENT_INTERNAL_ERROR -> GENERIC_FAILURE;
        };
    }
}

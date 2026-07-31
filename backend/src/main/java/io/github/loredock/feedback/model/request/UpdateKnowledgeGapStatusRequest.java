package io.github.loredock.feedback.model.request;

import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;
import jakarta.validation.constraints.NotNull;

/** 管理员设置目标处理状态的 HTTP 请求。 */
public record UpdateKnowledgeGapStatusRequest(@NotNull KnowledgeGapStatus status) {
}

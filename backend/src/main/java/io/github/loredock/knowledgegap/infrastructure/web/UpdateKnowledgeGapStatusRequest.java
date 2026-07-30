package io.github.loredock.knowledgegap.infrastructure.web;

import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import jakarta.validation.constraints.NotNull;

/** 管理员设置目标处理状态的 HTTP 请求。 */
public record UpdateKnowledgeGapStatusRequest(@NotNull KnowledgeGapStatus status) {
}

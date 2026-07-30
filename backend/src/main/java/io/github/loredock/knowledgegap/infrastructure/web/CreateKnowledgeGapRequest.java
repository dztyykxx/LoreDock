package io.github.loredock.knowledgegap.infrastructure.web;

import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 成员创建知识缺口的 HTTP 请求；关联问答时 question 不作为事实来源。 */
public record CreateKnowledgeGapRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Size(max = 255) String branch,
        @NotNull KnowledgeGapType type,
        UUID questionId,
        @Size(max = 4000) String question,
        @Size(max = 2000) String note
) {
}

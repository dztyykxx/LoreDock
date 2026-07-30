package io.github.loredock.knowledgegap.application;

import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;

/** 管理列表可选范围、类型和状态过滤。 */
public record KnowledgeGapFilter(
        String projectIdentifier, String branch, KnowledgeGapType type, KnowledgeGapStatus status
) {
}

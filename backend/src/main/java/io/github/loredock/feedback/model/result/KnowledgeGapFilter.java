package io.github.loredock.feedback.model.result;

import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;
import io.github.loredock.feedback.model.enums.KnowledgeGapType;

/** 管理列表可选范围、类型和状态过滤。 */
public record KnowledgeGapFilter(
        String projectIdentifier, String branch, KnowledgeGapType type, KnowledgeGapStatus status
) {
}

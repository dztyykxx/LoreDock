package io.github.loredock.feedback.model.command;

import io.github.loredock.feedback.model.KnowledgeGapText;
import io.github.loredock.feedback.model.enums.KnowledgeGapType;

/** 成员创建知识缺口的规范化输入；关联问答时 question 会被服务端事实覆盖。 */
public record CreateKnowledgeGapCommand(
        String operatorId,
        String idempotencyKey,
        String projectIdentifier,
        String branch,
        KnowledgeGapType type,
        Long questionId,
        String question,
        String note
) {
    /** @return 完成通用字段和 Unicode 边界校验的命令 */
    public static CreateKnowledgeGapCommand of(
            String operatorId, String idempotencyKey, String projectIdentifier, String branch,
            KnowledgeGapType type, Long questionId, String question, String note
    ) {
        if (operatorId == null || operatorId.isBlank() || projectIdentifier == null || projectIdentifier.isBlank()
                || type == null) {
            throw new IllegalArgumentException("knowledge gap required field is missing");
        }
        String normalizedQuestion = questionId == null ? KnowledgeGapText.question(question) : null;
        return new CreateKnowledgeGapCommand(
                operatorId.strip(), KnowledgeGapText.idempotencyKey(idempotencyKey), projectIdentifier.strip(),
                branch == null || branch.isBlank() ? null : branch.strip(), type, questionId,
                normalizedQuestion, KnowledgeGapText.note(note));
    }
}

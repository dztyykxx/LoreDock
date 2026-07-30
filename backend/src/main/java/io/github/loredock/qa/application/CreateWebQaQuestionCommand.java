package io.github.loredock.qa.application;

import io.github.loredock.qa.domain.WebQaIdempotencyKey;
import io.github.loredock.qa.domain.WebQaQuestionText;

/**
 * @param operatorId 当前认证操作者
 * @param idempotencyKey 当前操作者范围的客户端幂等键
 * @param projectIdentifier 项目标识
 * @param branch 可空分支；空值由用例解析为 main
 * @param question 本次独立问题，不包含历史正文
 */
public record CreateWebQaQuestionCommand(
        String operatorId,
        String operatorRole,
        WebQaIdempotencyKey idempotencyKey,
        String projectIdentifier,
        String branch,
        WebQaQuestionText question
) {
    /** @return 经过 Unicode 规范化和边界检查的创建命令 */
    public static CreateWebQaQuestionCommand of(
            String operatorId,
            String operatorRole,
            String idempotencyKey,
            String projectIdentifier,
            String branch,
            String question
    ) {
        return new CreateWebQaQuestionCommand(
                requireText(operatorId, "operator"), requireText(operatorRole, "operator role"),
                WebQaIdempotencyKey.of(idempotencyKey),
                requireText(projectIdentifier, "project identifier"), normalizeOptional(branch),
                WebQaQuestionText.of(question));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

package io.github.loredock.qa.model.response;

import io.github.loredock.qa.api.QaQuestion;
import java.time.Instant;

/**
 * 运行当时保存的安全来源卡片；字段为空表示该来源类型不适用或历史运行未记录。
 */
public record WebQaCitationResponse(
        int order,
        QaQuestion.EvidenceSourceType sourceType,
        String projectIdentifier,
        String branch,
        String commit,
        String repositoryPath,
        String title,
        Instant sourceUpdatedAt,
        String scopeType,
        String knowledgeSourceType,
        String wikiUrl,
        String originalFilename
) {
}

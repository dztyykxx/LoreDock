package io.github.loredock.knowledge.model.request;

import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import jakarta.validation.constraints.NotNull;

/**
 * 写入请求中的来源元数据；WIKI 必须有 HTTP(S) URL，UPLOAD 必须有原文件名，所有文本均不可信。
 *
 * @param type 来源类型
 * @param wikiUrl 原 Wiki URL
 * @param originalFilename 原文件名
 * @param curationNote 人工整理说明
 */
public record DocumentSourceRequest(
        @NotNull DocumentSourceType type,
        String wikiUrl,
        String originalFilename,
        String curationNote
) {
}

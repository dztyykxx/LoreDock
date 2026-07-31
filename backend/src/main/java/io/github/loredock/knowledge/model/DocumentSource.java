package io.github.loredock.knowledge.model;

import io.github.loredock.knowledge.model.enums.DocumentSourceType;

/**
 * 知识来源契约；字段是否必填由来源类型决定，上传文件名始终作为不可信元数据显示。
 *
 * @param type 来源类别
 * @param wikiUrl 原 Wiki 的 HTTP(S) 地址
 * @param originalFilename 原始上传文件名
 * @param curationNote 人工整理说明
 */
public record DocumentSource(
        DocumentSourceType type,
        String wikiUrl,
        String originalFilename,
        String curationNote
) {

    public DocumentSource {
        if (type == null) {
            throw new IllegalArgumentException("document source type is required");
        }
        wikiUrl = normalizeOptional(wikiUrl, KnowledgeDocumentLimits.WIKI_URL_MAX_CODE_POINTS, "wiki URL");
        originalFilename = normalizeOptional(
                originalFilename, KnowledgeDocumentLimits.ORIGINAL_FILENAME_MAX_CODE_POINTS, "original filename");
        curationNote = normalizeOptional(
                curationNote, KnowledgeDocumentLimits.CURATION_NOTE_MAX_CODE_POINTS, "curation note");
        switch (type) {
            case MANUAL -> {
                if (wikiUrl != null || originalFilename != null) {
                    throw new IllegalArgumentException("manual source contains residual external fields");
                }
            }
            case WIKI -> validateWikiUrl(wikiUrl);
            case UPLOAD -> {
                if (originalFilename == null) {
                    throw new IllegalArgumentException("upload source requires original filename");
                }
                if (wikiUrl != null) {
                    throw new IllegalArgumentException("upload source contains wiki URL");
                }
            }
        }
    }

    private static String normalizeOptional(String value, int maxCodePoints, String field) {
        String normalized = DocumentTextRules.normalizedOptional(value);
        if (normalized != null) {
            DocumentTextRules.requireMaxCodePoints(normalized, maxCodePoints, field);
        }
        return normalized;
    }

    private static void validateWikiUrl(String value) {
        if (value == null) {
            throw new IllegalArgumentException("wiki source requires URL");
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("wiki source URL must be HTTP(S)");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("wiki source URL is invalid", exception);
        }
    }
}

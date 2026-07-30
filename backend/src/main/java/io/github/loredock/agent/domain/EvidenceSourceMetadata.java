package io.github.loredock.agent.domain;

import java.net.URI;
import java.util.Set;

/**
 * 运行当时的可公开知识来源附属快照；不包含正文、整理说明、对象键或服务器路径。
 * 历史运行没有版本化 metadata 时，各字段保持空值，由调用方显示为“历史运行未记录”。
 */
public record EvidenceSourceMetadata(
        String schemaVersion,
        String scopeType,
        String knowledgeSourceType,
        String wikiUrl,
        String originalFilename
) {
    public static final String CURRENT_SCHEMA_VERSION = "knowledge-source-v1";
    private static final Set<String> SCOPES = Set.of("GLOBAL", "PROJECT", "BRANCH");
    private static final Set<String> SOURCES = Set.of("MANUAL", "WIKI", "UPLOAD");

    public EvidenceSourceMetadata {
        if (schemaVersion == null) {
            if (scopeType != null || knowledgeSourceType != null || wikiUrl != null || originalFilename != null) {
                throw new IllegalArgumentException("unversioned evidence source metadata");
            }
        } else {
            if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("unsupported evidence source metadata version");
            }
            if (!SCOPES.contains(scopeType) || !SOURCES.contains(knowledgeSourceType)) {
                throw new IllegalArgumentException("invalid evidence source metadata type");
            }
            validateSourceFields(knowledgeSourceType, wikiUrl, originalFilename);
        }
    }

    /** @return 迁移前未记录附属来源的安全降级快照 */
    public static EvidenceSourceMetadata historicalUnknown() {
        return new EvidenceSourceMetadata(null, null, null, null, null);
    }

    private static void validateSourceFields(String sourceType, String wikiUrl, String originalFilename) {
        switch (sourceType) {
            case "MANUAL" -> {
                if (wikiUrl != null || originalFilename != null) {
                    throw new IllegalArgumentException("manual evidence contains external source fields");
                }
            }
            case "WIKI" -> {
                if (!isHttpUrl(wikiUrl) || originalFilename != null) {
                    throw new IllegalArgumentException("wiki evidence source is invalid");
                }
            }
            case "UPLOAD" -> {
                if (wikiUrl != null || originalFilename == null || originalFilename.isBlank()) {
                    throw new IllegalArgumentException("upload evidence source is invalid");
                }
            }
            default -> throw new IllegalArgumentException("unknown evidence source type");
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}

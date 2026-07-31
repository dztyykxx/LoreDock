package io.github.loredock.knowledge.model;

/**
 * 知识目录的逻辑路径契约，根目录使用空字符串；它不是服务器文件路径。
 *
 * @param value 使用正斜杠分隔的逻辑路径
 */
public record DocumentDirectory(String value) {

    public DocumentDirectory {
        String normalized = DocumentTextRules.normalizedOptional(value);
        value = normalized == null ? "" : normalized;
        DocumentTextRules.requireMaxCodePoints(
                value, KnowledgeDocumentLimits.DIRECTORY_MAX_CODE_POINTS, "document directory");
        validateLogicalPath(value);
    }

    private static void validateLogicalPath(String value) {
        if (value.isEmpty()) {
            return;
        }
        // 目录只表达知识分类，不是文件路径；拒绝任何可能被文件系统解释为逃逸或歧义的形式。
        if (value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("document directory is not a logical path");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("document directory contains control character");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("document directory contains ambiguous segment");
            }
        }
    }
}

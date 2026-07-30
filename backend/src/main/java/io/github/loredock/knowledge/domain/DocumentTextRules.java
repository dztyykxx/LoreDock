package io.github.loredock.knowledge.domain;

import java.text.Normalizer;

/** 知识文本值对象共享的 Unicode 规范化与码点长度规则。 */
final class DocumentTextRules {

    private DocumentTextRules() {
    }

    static String normalizedRequired(String value, int maxCodePoints, String field) {
        String normalized = normalizedOptional(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        requireMaxCodePoints(normalized, maxCodePoints, field);
        return normalized;
    }

    static String normalizedOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        return normalized.isEmpty() ? null : normalized;
    }

    static void requireMaxCodePoints(String value, int maxCodePoints, String field) {
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException(field + " is too long");
        }
    }
}

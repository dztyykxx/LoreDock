package io.github.loredock.feedback.model;

import java.text.Normalizer;

/** 知识缺口问题和可选补充说明的 Unicode 规范化规则。 */
public final class KnowledgeGapText {
    private KnowledgeGapText() {
    }

    /** @return 1～2000 个 Unicode 字符的规范化问题 */
    public static String question(String value) {
        return required(value, 2000, "knowledge gap question");
    }

    /** @return 可空且不超过 1000 个 Unicode 字符的规范化说明 */
    public static String note(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return bounded(Normalizer.normalize(value, Normalizer.Form.NFC).strip(), 1000, "knowledge gap note");
    }

    /** @return 1～128 个 Unicode 字符的规范化客户端幂等键 */
    public static String idempotencyKey(String value) {
        return required(value, 128, "knowledge gap idempotency key");
    }

    private static String required(String value, int maximum, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return bounded(Normalizer.normalize(value, Normalizer.Form.NFC).strip(), maximum, field);
    }

    private static String bounded(String value, int maximum, String field) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return value;
    }
}

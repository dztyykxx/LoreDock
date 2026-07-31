package io.github.loredock.qa.model;

import java.text.Normalizer;

/** 规范化且按 Unicode code point 限制的单次 Web 问题。 */
public record WebQaQuestionText(String value) {
    public static final int MAX_CODE_POINTS = 2000;

    public WebQaQuestionText {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        int length = value.codePointCount(0, value.length());
        if (length > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("question exceeds Unicode limit");
        }
    }

    /** @param value 原始问题 @return 去除边缘空白并按 NFC 规范化的问题 */
    public static WebQaQuestionText of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("question is required");
        }
        return new WebQaQuestionText(Normalizer.normalize(value.strip(), Normalizer.Form.NFC));
    }

    /** @return Unicode code point 数量 */
    public int codePointLength() {
        return value.codePointCount(0, value.length());
    }
}

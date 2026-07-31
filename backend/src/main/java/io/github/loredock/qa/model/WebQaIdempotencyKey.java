package io.github.loredock.qa.model;

import java.text.Normalizer;

/** 当前操作者范围内的一次 Web 问答客户端幂等键。 */
public record WebQaIdempotencyKey(String value) {
    public static final int MAX_CODE_POINTS = 128;

    public WebQaIdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("idempotency key exceeds Unicode limit");
        }
    }

    /** @param value 原始客户端键 @return 去除边缘空白并按 NFC 规范化的键 */
    public static WebQaIdempotencyKey of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        return new WebQaIdempotencyKey(Normalizer.normalize(value.strip(), Normalizer.Form.NFC));
    }
}

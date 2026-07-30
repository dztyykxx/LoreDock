package io.github.loredock.qa.infrastructure.web;

/** 解析标准 `Last-Event-ID` 与查询参数的 SSE 续读位置。 */
final class WebQaSseCursor {
    private WebQaSseCursor() {
    }

    /** @return 两种输入一致后的非负已消费序号，均省略时为零 */
    static long resolve(String lastEventId, Long afterSequence) {
        Long headerSequence = parse(lastEventId);
        if (afterSequence != null && afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        if (headerSequence != null && afterSequence != null && !headerSequence.equals(afterSequence)) {
            throw new IllegalArgumentException("SSE cursor sources conflict");
        }
        if (headerSequence != null) {
            return headerSequence;
        }
        return afterSequence == null ? 0 : afterSequence;
    }

    private static Long parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Last-Event-ID is blank");
        }
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 0) {
                throw new IllegalArgumentException("Last-Event-ID must be non-negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID is invalid", exception);
        }
    }
}

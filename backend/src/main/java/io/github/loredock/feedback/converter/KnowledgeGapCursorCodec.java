package io.github.loredock.feedback.converter;

import io.github.loredock.feedback.model.snapshot.KnowledgeGapCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 不透明知识缺口复合游标编解码器。 */
public final class KnowledgeGapCursorCodec {
    private KnowledgeGapCursorCodec() {
    }

    /** @return URL 安全且不直接暴露分页字段的游标 */
    public static String encode(KnowledgeGapCursor cursor) {
        String raw = cursor.createdAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 经格式校验的复合游标 */
    public static KnowledgeGapCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator < 1 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("knowledge gap cursor is invalid");
            }
            return new KnowledgeGapCursor(
                    Instant.parse(raw.substring(0, separator)), Long.valueOf(raw.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("knowledge gap cursor is invalid", exception);
        }
    }
}

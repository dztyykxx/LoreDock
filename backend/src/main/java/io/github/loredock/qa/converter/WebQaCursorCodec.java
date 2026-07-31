package io.github.loredock.qa.converter;

import io.github.loredock.qa.model.snapshot.WebQaCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** 不暴露内部游标字段格式的 URL-safe 编解码器。 */
public final class WebQaCursorCodec {
    private static final int MAX_ENCODED_LENGTH = 256;

    private WebQaCursorCodec() {
    }

    /** @param cursor 分页位置 @return URL-safe 不透明游标 */
    public static String encode(WebQaCursor cursor) {
        String payload = cursor.createdAt() + "\n" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param encoded URL-safe 不透明游标
     * @return 解码后的分页位置
     * @throws IllegalArgumentException 游标为空、过长、被篡改或格式不兼容
     */
    public static WebQaCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] fields = payload.split("\\n", -1);
            if (fields.length != 2) {
                throw invalid();
            }
            return new WebQaCursor(Instant.parse(fields[0]), Long.valueOf(fields[1]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid web QA cursor");
    }
}

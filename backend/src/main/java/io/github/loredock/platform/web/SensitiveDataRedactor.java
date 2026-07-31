package io.github.loredock.platform.web;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 在诊断信息进入日志或任务错误记录前集中移除常见凭据、连接串和绝对路径。
 */
@Component
public class SensitiveDataRedactor {

    private static final int MAX_DIAGNOSTIC_LENGTH = 32_000;
    private static final Pattern HTTP_CREDENTIAL_HEADER = Pattern.compile(
            "(?i)(authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+"
    );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(password|token|authorization|api[_-]?key|digest)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern SHA256_DIGEST = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s]+", Pattern.MULTILINE);
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?:(?:[A-Za-z]:\\\\)|/)(?:[^\\s/:]+[/\\\\]){1,}[^\\s:]*"
    );

    /**
     * 对诊断文本脱敏并限制长度，避免敏感信息泄露和超大异常占满日志。
     *
     * @param value 原始诊断文本，可以为空
     * @return 可安全记录的文本
     */
    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return "[NO_DIAGNOSTIC_MESSAGE]";
        }
        String redacted = HTTP_CREDENTIAL_HEADER.matcher(value).replaceAll("$1=[REDACTED]");
        redacted = CREDENTIAL.matcher(redacted).replaceAll("$1=[REDACTED]");
        redacted = SHA256_DIGEST.matcher(redacted).replaceAll("[REDACTED_SHA256]");
        redacted = JDBC_URL.matcher(redacted).replaceAll("[REDACTED_JDBC_URL]");
        redacted = ABSOLUTE_PATH.matcher(redacted).replaceAll("[REDACTED_PATH]");
        return redacted.substring(0, Math.min(redacted.length(), MAX_DIAGNOSTIC_LENGTH));
    }
}

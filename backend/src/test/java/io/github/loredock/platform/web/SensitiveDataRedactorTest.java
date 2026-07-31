package io.github.loredock.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    /**
     * 业务目的：异常诊断进入日志前必须移除凭据、内部连接串和绝对路径，防止日志成为敏感信息旁路。
     */
    @Test
    void redactsCredentialsConnectionStringsAndAbsolutePaths() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();

        String redacted = redactor.redact(
                "password=secret-value token=abc123 jdbc:postgresql://internal/db /Users/demo/private/file");

        assertThat(redacted)
                .doesNotContain("secret-value", "abc123", "jdbc:postgresql", "/Users/demo")
                .contains("[REDACTED]");
    }

    /**
     * 业务目的：安全日志还必须移除 Cookie、Authorization 和裸 Token 摘要，防止认证边界的凭据副本进入诊断日志。
     */
    @Test
    void redactsHttpCredentialsAndBareSha256Digest() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();
        String digest = "a".repeat(64);

        String redacted = redactor.redact(
                "Cookie: loredock_session=session-secret Authorization: Bearer raw-token digest=" + digest);

        assertThat(redacted)
                .doesNotContain("session-secret", "raw-token", digest)
                .contains("[REDACTED]");
    }
}

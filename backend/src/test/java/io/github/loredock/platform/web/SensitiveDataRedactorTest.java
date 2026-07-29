package io.github.loredock.platform.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    /**
     * 业务目的：异常诊断进入日志前必须移除凭据、内部连接串和绝对路径，防止日志成为敏感信息旁路。
     */
    @Test
    void 脱敏诊断文本中的凭据连接串和绝对路径() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();

        String redacted = redactor.redact(
                "password=secret-value token=abc123 jdbc:postgresql://internal/db /Users/demo/private/file");

        assertThat(redacted)
                .doesNotContain("secret-value", "abc123", "jdbc:postgresql", "/Users/demo")
                .contains("[REDACTED]");
    }
}

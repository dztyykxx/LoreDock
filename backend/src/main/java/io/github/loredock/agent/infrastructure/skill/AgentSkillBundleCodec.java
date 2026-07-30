package io.github.loredock.agent.infrastructure.skill;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 使用固定分隔符编码 Skill Markdown 和 schema，确保内容哈希跨运行稳定。 */
@Component
public class AgentSkillBundleCodec {

    private static final String SEPARATOR = "\n---LOREDOCK-OUTPUT-SCHEMA---\n";

    /** @return 可按 SHA-256 校验的稳定字节 */
    public byte[] encode(String markdown, String schema) {
        return (markdown + SEPARATOR + schema).getBytes(StandardCharsets.UTF_8);
    }

    /** @return 解码后的 Markdown 和 schema */
    public Content decode(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        int position = value.indexOf(SEPARATOR);
        if (position < 0 || value.indexOf(SEPARATOR, position + SEPARATOR.length()) >= 0) {
            throw new IllegalArgumentException("Skill 内容包结构无效");
        }
        return new Content(value.substring(0, position), value.substring(position + SEPARATOR.length()));
    }

    /** Skill 内容包的两个组成部分。 */
    public record Content(String markdown, String outputSchema) {
    }
}

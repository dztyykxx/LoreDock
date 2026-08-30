package io.github.loredock.agent.service;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 上下文 Token 估算器（设计文档 §9）。
 *
 * <p>项目未锁定模型 Tokenizer 依赖，当前实现使用保守的 UTF-8 字节上界估算：
 * {@code tokens = ceil(utf8Bytes / 3)}。中文约 3 字节/字符 = 约 1 token/字符切合实际消耗，
 * 英文文本实际约 4 字节/token，按上式估算偏保守（高估），不会放行超限输入。</p>
 *
 * <p>估算模式随结果记录为 {@code UTF8_BYTE_BOUND}，日志必须标明，不得把估算误报为模型实际 usage。</p>
 */
public class ContextTokenEstimator {

    /** 估算模式标识：保守 UTF-8 字节上界（设计文档 §9 承认的 fallback 模式）。 */
    public static final String UTF8_BYTE_BOUND = "UTF8_BYTE_BOUND";

    /** 每 token 对应的 UTF-8 字节数上界（低估即危险，故取 3 而非更激进的 2.5）。 */
    private static final double BYTES_PER_TOKEN_SAFE = 3.0;

    /** 估算结果：tokens 为客户端预算判定的权威计数，utf8Bytes 为次级计数，mode 为估算模式。 */
    public record Estimate(int tokens, int utf8Bytes, String mode) {
    }

    /**
     * @param messages 待估算的完整消息链（组装结果或守卫调用前的全量消息）
     * @return UTF-8 字节上界估算；空链返回 0
     */
    public Estimate estimate(List<Message> messages) {
        long bytes = messages == null ? 0 : messages.stream().mapToLong(this::utf8BytesOf).sum();
        int tokens = (int) Math.ceil(bytes / BYTES_PER_TOKEN_SAFE);
        return new Estimate(tokens, (int) bytes, UTF8_BYTE_BOUND);
    }

    private long utf8BytesOf(Message message) {
        if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage toolResponse) {
            // 工具响应的正文在 responses 中（getText 通常为空），框架会原样注入模型，必须计入估算。
            return toolResponse.getResponses().stream()
                    .mapToLong(response -> utf8(response.name()) + utf8(response.responseData()) + 8).sum();
        }
        String text = message == null ? null : message.getText();
        if (text == null) {
            return 0;
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static long utf8(String value) {
        return value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** @return 单个消息的估算 token 数（供守卫逐条日志使用）。 */
    public int estimateTokens(Message message) {
        return (int) Math.ceil(utf8BytesOf(message) / BYTES_PER_TOKEN_SAFE);
    }
}

package io.github.loredock.agent.model.context;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 准备完成的模型输入（设计文档 §4.2）：{@code messages} 是唯一可交给 ReactAgent / ChatModel
 * 的模型输入；{@code receipt} 只用于日志、运行观测与测试，不含消息正文。
 */
public record PreparedModelContext(
        List<Message> messages,
        ContextReceipt receipt
) {
    public PreparedModelContext {
        messages = List.copyOf(messages);
    }
}

package io.github.loredock.qa.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 浏览器问答创建请求。
 *
 * @param idempotencyKey 客户端在不确定重试期间保持不变的键
 * @param branch 可选目标分支；省略时由服务端解析 main
 * @param conversationId 可选既有会话；省略时创建新会话
 * @param question 仅本轮问题正文，Unicode 上限由领域值对象按码点校验
 */
public record CreateWebQaQuestionRequest(
        @NotBlank @Size(max = 128) String idempotencyKey,
        @Size(max = 255) String branch,
        Long conversationId,
        @NotBlank String question
) {
}

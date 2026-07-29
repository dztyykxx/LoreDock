package io.github.loredock.identity.application;

/**
 * MCP 共享高熵 Token 校验端口；该机器身份与 Web 会话和角色授权相互独立。
 */
@FunctionalInterface
public interface McpTokenValidator {

    /**
     * 校验单个原始 Token。实现不得记录、返回或持久化来值。
     *
     * @param rawToken 请求中解析出的高熵 Token
     * @return Token 是否与配置摘要匹配
     */
    boolean isValid(String rawToken);
}

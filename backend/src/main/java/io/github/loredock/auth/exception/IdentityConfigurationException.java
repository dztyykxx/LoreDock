package io.github.loredock.auth.exception;

/**
 * 固定身份配置不满足安全就绪条件；消息只描述失败类别，不包含配置值。
 */
public class IdentityConfigurationException extends RuntimeException {

    /**
     * @param reason 不含账号、密码或哈希的安全原因类别
     */
    public IdentityConfigurationException(String reason) {
        super("identity configuration invalid: " + reason);
    }
}

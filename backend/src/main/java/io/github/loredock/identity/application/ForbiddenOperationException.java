package io.github.loredock.identity.application;

/**
 * 已登录身份角色不足；客户端提交的角色值不能改变该结论。
 */
public class ForbiddenOperationException extends RuntimeException {

    /**
     * 创建不泄露受保护资源细节的禁止访问失败。
     */
    public ForbiddenOperationException() {
        super("web role forbidden");
    }
}

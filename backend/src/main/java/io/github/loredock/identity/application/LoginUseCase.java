package io.github.loredock.identity.application;

import io.github.loredock.identity.domain.AuthenticatedActor;

/**
 * 校验固定 Web 账号凭据；成功结果可用于建立当前浏览器会话。
 */
@FunctionalInterface
public interface LoginUseCase {

    /**
     * 校验登录凭据。未知账号与错误密码必须产生相同的对外失败语义。
     *
     * @param command 单次登录输入
     * @return 不含密码与会话令牌的已认证身份
     * @throws InvalidCredentialsException 账号不存在或密码不匹配
     */
    AuthenticatedActor login(LoginCommand command);
}

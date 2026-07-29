package io.github.loredock.identity.application;

import io.github.loredock.identity.domain.AuthenticatedActor;

/**
 * 查询当前 Web 请求关联的有效会话身份。
 */
@FunctionalInterface
public interface CurrentSessionUseCase {

    /**
     * @return 不含会话令牌的当前身份摘要
     * @throws LoginRequiredException 请求没有有效 Web 会话
     */
    AuthenticatedActor currentSession();
}

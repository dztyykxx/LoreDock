package io.github.loredock.identity.application;

import io.github.loredock.identity.domain.AuthenticatedActor;

import java.util.Optional;

/**
 * Web 会话基础设施端口，隔离应用用例与具体 Sa-Token API。
 */
public interface WebSessionPort {

    /**
     * 为当前浏览器请求建立或替换会话。
     *
     * @param actor 服务端已认证身份
     */
    void establish(AuthenticatedActor actor);

    /**
     * @return 当前有效 Web 会话身份；无会话时为空
     */
    Optional<AuthenticatedActor> current();

    /**
     * 清除当前浏览器会话；没有会话时不得失败。
     */
    void clear();
}

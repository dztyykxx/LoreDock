package io.github.loredock.platform.audit;

/**
 * 提供当前审计操作者；认证接入前由平台实现返回显式 SYSTEM 身份。
 */
@FunctionalInterface
public interface ActorProvider {

    /**
     * @return 当前操作者的稳定标识
     */
    String currentActor();
}

package io.github.loredock.identity.application;

import java.util.Collection;
import java.util.Optional;

/**
 * 提供配置化固定账号的只读目录，使登录用例不依赖 Spring 配置结构。
 */
public interface FixedAccountDirectory {

    /**
     * 按稳定账号标识查找固定账号。
     *
     * @param username 客户端提交的账号标识
     * @return 匹配账号；不存在时为空，调用方不得据此向客户端暴露账号存在性
     */
    Optional<FixedAccount> findByUsername(String username);

    /**
     * 返回全部配置账号，供启动完整性校验使用。
     *
     * @return 不可由调用方修改的账号集合
     */
    Collection<FixedAccount> configuredAccounts();
}

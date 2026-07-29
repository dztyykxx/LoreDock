package io.github.loredock.identity.application;

/**
 * 人类密码校验端口；实现必须委托成熟 BCrypt 库，不得自行实现密码哈希算法。
 */
@FunctionalInterface
public interface PasswordVerifier {

    /**
     * 校验明文候选密码与已配置 BCrypt 哈希。
     *
     * @param rawPassword 仅在本次调用内使用的明文密码，禁止记录或缓存
     * @param passwordHash 已校验格式的 BCrypt 哈希，禁止对外返回
     * @return 是否匹配
     */
    boolean matches(String rawPassword, String passwordHash);
}

package io.github.loredock.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LoreDock 固定 Web 账号配置。必须包含一个 ADMIN 管理员账号，
 * MEMBER 组内共享只读账号可选，单管理员部署可以只配置一个账号。
 *
 * @param accounts 固定账号列表
 */
@ConfigurationProperties("loredock.identity.web")
public record FixedAccountsProperties(List<FixedAccountProperties> accounts) {
}

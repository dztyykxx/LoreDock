package io.github.loredock.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LoreDock 固定 Web 账号配置，必须恰好包含一个 ADMIN 和一个 MEMBER。
 *
 * @param accounts 固定账号列表
 */
@ConfigurationProperties("loredock.identity.web")
public record FixedAccountsProperties(List<FixedAccountProperties> accounts) {
}

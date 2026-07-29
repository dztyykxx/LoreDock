package io.github.loredock.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * LoreDock 固定 Web 账号配置，必须恰好包含一个 ADMIN 和一个 MEMBER。
 *
 * @param accounts 固定账号列表
 */
@ConfigurationProperties("loredock.identity.web")
public record FixedAccountsProperties(List<FixedAccountProperties> accounts) {
}

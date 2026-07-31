package io.github.loredock.platform.web;

import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 平台基础端口的默认实现配置；认证能力接入后只替换操作者提供者，不改变审计契约。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    /**
     * @return 使用 UTC 瞬间语义的系统时间提供者
     */
    @Bean
    public Clock timeProvider() {
        return Clock.systemUTC();
    }

    /**
     * @return T1 阶段明确的系统操作者
     */
    @Bean
    @ConditionalOnMissingBean(name = "auditActorSupplier")
    public Supplier<String> auditActorSupplier() {
        return () -> "SYSTEM";
    }
}

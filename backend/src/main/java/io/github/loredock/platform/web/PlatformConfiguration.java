package io.github.loredock.platform.web;

import io.github.loredock.platform.audit.ActorProvider;
import io.github.loredock.platform.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 平台基础端口的默认实现配置；认证能力接入后只替换操作者提供者，不改变审计契约。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    /**
     * @return 使用 UTC 瞬间语义的系统时间提供者
     */
    @Bean
    public TimeProvider timeProvider() {
        return Instant::now;
    }

    /**
     * @return T1 阶段明确的系统操作者
     */
    @Bean
    public ActorProvider actorProvider() {
        return () -> "SYSTEM";
    }
}

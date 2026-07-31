package io.github.loredock.storage.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 本地对象存储配置边界，集中约束持久化根目录。
 *
 * @param root 对象持久化根目录
 */
@Validated
@ConfigurationProperties("loredock.storage")
public record StorageProperties(@NotNull Path root) {
}

package io.github.loredock.storage.infrastructure.filesystem;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 本地对象存储配置边界，集中约束持久化根目录。
 *
 * @param root 对象持久化根目录
 */
@Validated
@ConfigurationProperties("loredock.storage")
public record StorageProperties(@NotNull Path root) {
}

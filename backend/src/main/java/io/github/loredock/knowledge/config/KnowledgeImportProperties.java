package io.github.loredock.knowledge.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 不可信知识文件的资源上限配置。所有字节上限使用 {@link DataSize}，避免把字节数、条目数和压缩比混为一谈。
 *
 * @param maxUploadSize 单次上传文件的最大字节数
 * @param maxArchiveEntries ZIP 中央目录允许的最大条目数
 * @param maxEntryUncompressedSize 单个 ZIP 条目允许的最大展开字节数
 * @param maxArchiveUncompressedSize 一个 ZIP 允许的累计展开字节数
 * @param maxCompressionRatio ZIP 条目允许的最大展开/压缩字节比
 */
@Validated
@ConfigurationProperties("loredock.knowledge.importing")
public record KnowledgeImportProperties(
        @NotNull DataSize maxUploadSize,
        @Min(1) int maxArchiveEntries,
        @NotNull DataSize maxEntryUncompressedSize,
        @NotNull DataSize maxArchiveUncompressedSize,
        @NotNull @DecimalMin(value = "1.0", inclusive = true) BigDecimal maxCompressionRatio
) {
    public KnowledgeImportProperties {
        requirePositive(maxUploadSize, "知识导入上传上限必须大于零");
        requirePositive(maxEntryUncompressedSize, "知识导入单条目展开上限必须大于零");
        requirePositive(maxArchiveUncompressedSize, "知识导入累计展开上限必须大于零");
        if (maxArchiveUncompressedSize.compareTo(maxEntryUncompressedSize) < 0) {
            throw new IllegalArgumentException("知识导入累计展开上限不能小于单条目展开上限");
        }
    }

    private static void requirePositive(DataSize value, String message) {
        if (value != null && value.toBytes() <= 0) {
            throw new IllegalArgumentException(message);
        }
    }
}

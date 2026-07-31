package io.github.loredock.code.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * 代码 ZIP、索引和响应的硬边界。物理目录由服务端配置，任何 HTTP 或应用命令都不得覆盖。
 *
 * @param maxUploadSize 外层 ZIP 计数字节上限，最大允许 100 MiB
 * @param maxArchiveEntries ZIP 中央目录最大条目数
 * @param maxArchiveEntryUncompressedSize 单个 ZIP 条目的结构性展开硬上限
 * @param maxIndexedFileSize 单个可索引文本的完整字节上限
 * @param maxArchiveUncompressedSize ZIP 声明累计展开量上限
 * @param maxCompressionRatio 单条目最大展开/压缩比
 * @param maxSearchSnippetChars 单个搜索命中纯文本片段上限
 * @param workRoot 服务生成的临时任务根目录
 * @param indexRoot 服务生成的 Lucene generation 根目录
 */
@Validated
@ConfigurationProperties("loredock.code.snapshot")
public record CodeSnapshotProperties(
        @NotNull DataSize maxUploadSize,
        @Min(1) int maxArchiveEntries,
        @NotNull DataSize maxArchiveEntryUncompressedSize,
        @NotNull DataSize maxIndexedFileSize,
        @NotNull DataSize maxArchiveUncompressedSize,
        @NotNull @DecimalMin(value = "1.0", inclusive = true) BigDecimal maxCompressionRatio,
        @Min(1) int maxSearchSnippetChars,
        @NotNull Path workRoot,
        @NotNull Path indexRoot
) {
    public static final DataSize ABSOLUTE_MAX_UPLOAD_SIZE = DataSize.ofMegabytes(100);

    /** 在应用就绪前验证代码快照资源上限和物理目录隔离。 */
    public CodeSnapshotProperties {
        requirePositive(maxUploadSize, "代码快照上传上限必须大于零");
        requirePositive(maxArchiveEntryUncompressedSize, "代码 ZIP 单条目展开上限必须大于零");
        requirePositive(maxIndexedFileSize, "代码单文件索引上限必须大于零");
        requirePositive(maxArchiveUncompressedSize, "代码快照累计展开上限必须大于零");
        if (maxUploadSize != null && maxUploadSize.compareTo(ABSOLUTE_MAX_UPLOAD_SIZE) > 0) {
            throw new IllegalArgumentException("代码快照上传上限不得超过 100 MiB");
        }
        if (maxArchiveEntryUncompressedSize != null && maxIndexedFileSize != null
                && maxArchiveEntryUncompressedSize.compareTo(maxIndexedFileSize) < 0) {
            throw new IllegalArgumentException("ZIP 单条目展开上限不能小于单文件索引上限");
        }
        if (maxArchiveUncompressedSize != null && maxArchiveEntryUncompressedSize != null
                && maxArchiveUncompressedSize.compareTo(maxArchiveEntryUncompressedSize) < 0) {
            throw new IllegalArgumentException("累计展开上限不能小于 ZIP 单条目展开上限");
        }
        if (workRoot != null && indexRoot != null) {
            Path work = workRoot.toAbsolutePath().normalize();
            Path index = indexRoot.toAbsolutePath().normalize();
            if (work.startsWith(index) || index.startsWith(work)) {
                throw new IllegalArgumentException("代码任务目录与索引目录必须隔离");
            }
        }
    }

    private static void requirePositive(DataSize value, String message) {
        if (value != null && value.toBytes() <= 0) {
            throw new IllegalArgumentException(message);
        }
    }
}

package io.github.loredock.knowledge.service.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonsCompressCompatibilityTest {

    @TempDir
    private Path temporaryDirectory;

    /**
     * 业务目的：锁定的 Commons Compress 必须能在 Java 21 下按中央目录读取 Unicode ZIP 条目，
     * 防止升级构建环境或依赖后破坏后续路径与条目类型安全检查的基础能力。
     */
    @Test
    void commonsCompressReadsUnicodeEntryFromCentralDirectoryOnJava21() throws Exception {
        Path archive = temporaryDirectory.resolve("knowledge.zip");
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("业务/说明.md"));
            output.write("安全示例".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (ZipFile zipFile = ZipFile.builder().setPath(archive).get()) {
            ZipArchiveEntry entry = zipFile.getEntry("业务/说明.md");

            assertThat(entry).isNotNull();
            assertThat(zipFile.getInputStream(entry).readAllBytes())
                    .isEqualTo("安全示例".getBytes(StandardCharsets.UTF_8));
        }
    }
}

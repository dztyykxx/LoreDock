package io.github.loredock.code.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.config.CodeSnapshotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class CodeSnapshotPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "loredock.code.snapshot.max-upload-size=100MB",
                    "loredock.code.snapshot.max-archive-entries=50000",
                    "loredock.code.snapshot.max-archive-entry-uncompressed-size=100MB",
                    "loredock.code.snapshot.max-indexed-file-size=2MB",
                    "loredock.code.snapshot.max-archive-uncompressed-size=1GB",
                    "loredock.code.snapshot.max-compression-ratio=100",
                    "loredock.code.snapshot.max-search-snippet-chars=2000",
                    "loredock.code.snapshot.work-root=build/work/code",
                    "loredock.code.snapshot.index-root=build/indexes/code");

    /**
     * 业务目的：代码资源上限和物理根目录必须以强类型完整绑定，防止字节、比例、条目数和服务器路径混用。
     */
    @Test
    void validCodeLimitsBindToStrongTypes() {
        contextRunner.run(context -> {
            CodeSnapshotProperties properties = context.getBean(CodeSnapshotProperties.class);
            assertThat(properties.maxUploadSize()).isEqualTo(DataSize.ofMegabytes(100));
            assertThat(properties.maxArchiveEntries()).isEqualTo(50_000);
            assertThat(properties.maxArchiveEntryUncompressedSize()).isEqualTo(DataSize.ofMegabytes(100));
            assertThat(properties.maxIndexedFileSize()).isEqualTo(DataSize.ofMegabytes(2));
            assertThat(properties.maxArchiveUncompressedSize()).isEqualTo(DataSize.ofGigabytes(1));
            assertThat(properties.maxCompressionRatio()).isEqualByComparingTo("100");
            assertThat(properties.workRoot()).isNotEqualTo(properties.indexRoot());
        });
    }

    /**
     * 业务目的：超过 100 MiB、矛盾展开量或重叠物理根目录必须在就绪前失败，防止运行期安全策略被错误配置放宽。
     */
    @Test
    void unsafeOrInconsistentConfigurationRejectsReadiness() {
        contextRunner.withPropertyValues("loredock.code.snapshot.max-upload-size=101MB")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "loredock.code.snapshot.max-indexed-file-size=3MB",
                        "loredock.code.snapshot.max-archive-entry-uncompressed-size=2MB")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "loredock.code.snapshot.work-root=build/code",
                        "loredock.code.snapshot.index-root=build/code")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CodeSnapshotProperties.class)
    static class TestConfiguration {
    }
}

package io.github.loredock.knowledge.service.importing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.knowledge.config.KnowledgeImportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class KnowledgeImportPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "loredock.knowledge.importing.max-upload-size=20MB",
                    "loredock.knowledge.importing.max-archive-entries=200",
                    "loredock.knowledge.importing.max-entry-uncompressed-size=2MB",
                    "loredock.knowledge.importing.max-archive-uncompressed-size=50MB",
                    "loredock.knowledge.importing.max-compression-ratio=100"
            );

    /**
     * 业务目的：所有导入资源上限必须绑定为有单位的强类型配置，防止字节、条目数和比例被混用。
     */
    @Test
    void validLimitsBindToStrongTypes() {
        contextRunner.run(context -> {
            KnowledgeImportProperties properties = context.getBean(KnowledgeImportProperties.class);

            assertThat(properties.maxUploadSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.maxArchiveEntries()).isEqualTo(200);
            assertThat(properties.maxEntryUncompressedSize()).isEqualTo(DataSize.ofMegabytes(2));
            assertThat(properties.maxArchiveUncompressedSize()).isEqualTo(DataSize.ofMegabytes(50));
            assertThat(properties.maxCompressionRatio()).isEqualByComparingTo("100");
        });
    }

    /**
     * 业务目的：累计展开上限小于单条目上限会让安全策略自相矛盾，必须在服务就绪前拒绝该配置。
     */
    @Test
    void inconsistentArchiveLimitsRejectReadiness() {
        contextRunner
                .withPropertyValues(
                        "loredock.knowledge.importing.max-entry-uncompressed-size=3MB",
                        "loredock.knowledge.importing.max-archive-uncompressed-size=2MB"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("知识导入累计展开上限不能小于单条目展开上限");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KnowledgeImportProperties.class)
    static class TestConfiguration {
    }
}

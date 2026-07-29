package io.github.loredock.project;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectPackageDependencyTest {

    /**
     * 业务目的：项目领域与应用层不得反向依赖 Controller、Mapper、持久化实体或文件路径，防止范围规则被基础设施绑死。
     */
    @Test
    void domainAndApplicationLayersDoNotDependOnProjectInfrastructure() throws IOException {
        Path capabilityRoot = Path.of("src/main/java/io/github/loredock/project");
        List<String> forbidden = List.of(
                "io.github.loredock.project.infrastructure",
                "org.apache.ibatis",
                "com.baomidou.mybatisplus",
                "java.nio.file"
        );

        try (var files = Files.walk(capabilityRoot)) {
            List<Path> protectedSources = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/domain/")
                            || path.toString().contains("/application/"))
                    .toList();
            assertThat(protectedSources).isNotEmpty();
            for (Path source : protectedSources) {
                String content = Files.readString(source);
                assertThat(forbidden).allSatisfy(dependency ->
                        assertThat(content).as(source + " 不应依赖 " + dependency).doesNotContain(dependency));
            }
        }
    }

    /**
     * 业务目的：项目能力不能退化成全局横向 MVC 目录，确保 Web、持久化和演示入口仍围绕项目能力内聚。
     */
    @Test
    void projectCapabilityKeepsFeatureFirstPackageStructure() {
        Path capabilityRoot = Path.of("src/main/java/io/github/loredock/project");

        assertThat(capabilityRoot.resolve("domain")).isDirectory();
        assertThat(capabilityRoot.resolve("application")).isDirectory();
        assertThat(capabilityRoot.resolve("infrastructure/persistence")).isDirectory();
        assertThat(capabilityRoot.resolve("infrastructure/web")).isDirectory();
        assertThat(capabilityRoot.resolve("infrastructure/demo")).isDirectory();
    }
}

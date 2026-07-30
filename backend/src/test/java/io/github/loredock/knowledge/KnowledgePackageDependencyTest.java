package io.github.loredock.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePackageDependencyTest {

    /**
     * 业务目的：知识领域规则必须保持纯 Java，防止状态机、范围和替代规则被 Spring、项目服务、数据库、文件系统或 HTTP 绑死。
     */
    @Test
    void knowledgeDomainDoesNotDependOnFrameworksOrOuterCapabilities() throws IOException {
        Path domainRoot = Path.of("src/main/java/io/github/loredock/knowledge/domain");
        List<String> forbiddenDependencies = List.of(
                "org.springframework",
                "com.baomidou.mybatisplus",
                "org.apache.ibatis",
                "io.github.loredock.project",
                "io.github.loredock.knowledge.application",
                "io.github.loredock.knowledge.infrastructure",
                "java.nio.file",
                "jakarta.servlet"
        );

        try (var files = Files.walk(domainRoot)) {
            List<Path> sources = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                String content = Files.readString(source);
                assertThat(forbiddenDependencies).allSatisfy(dependency ->
                        assertThat(content).as(source + " 不应依赖 " + dependency).doesNotContain(dependency));
            }
        }
    }

    /**
     * 业务目的：应用契约和用例不得反向依赖 Controller、Mapper 或持久化实体，防止业务能力只能经单一入口复用。
     */
    @Test
    void knowledgeApplicationDoesNotDependOnInfrastructure() throws IOException {
        assertSourcesDoNotContain(
                Path.of("src/main/java/io/github/loredock/knowledge/application"),
                List.of(
                        "io.github.loredock.knowledge.infrastructure",
                        "com.baomidou.mybatisplus",
                        "org.apache.ibatis",
                        "jakarta.servlet"
                )
        );
    }

    /**
     * 业务目的：数据库实体必须与领域聚合和 HTTP DTO 分离，防止注解映射对象越过适配器成为业务事实。
     */
    @Test
    void knowledgePersistenceEntitiesRemainInfrastructureOnlyDataCarriers() throws IOException {
        Path persistenceRoot = Path.of("src/main/java/io/github/loredock/knowledge/infrastructure/persistence");
        try (var files = Files.walk(persistenceRoot)) {
            List<Path> entities = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Entity.java"))
                    .toList();
            assertThat(entities).isNotEmpty();
            for (Path entity : entities) {
                String content = Files.readString(entity);
                assertThat(content).as(entity + " 不应依赖领域或 HTTP DTO")
                        .doesNotContain("io.github.loredock.knowledge.domain")
                        .doesNotContain("io.github.loredock.knowledge.application")
                        .doesNotContain("io.github.loredock.knowledge.infrastructure.web");
            }
        }
    }

    private void assertSourcesDoNotContain(Path root, List<String> forbiddenDependencies) throws IOException {
        try (var files = Files.walk(root)) {
            List<Path> sources = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
            assertThat(sources).isNotEmpty();
            for (Path source : sources) {
                String content = Files.readString(source);
                assertThat(forbiddenDependencies).allSatisfy(dependency ->
                        assertThat(content).as(source + " 不应依赖 " + dependency).doesNotContain(dependency));
            }
        }
    }
}

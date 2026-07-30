package io.github.loredock.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPackageDependencyTest {

    /**
     * 业务目的：Agent 领域层必须保持纯 Java，防止状态机和可信引用规则被 Spring、MyBatis 或文件系统绑死。
     */
    @Test
    void domainDoesNotDependOnFrameworkOrInfrastructureTypes() throws IOException {
        List<Path> classes = classFiles("domain");
        List<String> forbidden = List.of(
                "org/springframework", "com/baomidou", "jakarta/servlet", "java/nio/file", "/infrastructure/");

        for (Path classFile : classes) {
            String constantPool = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            assertThat(forbidden).allSatisfy(value -> assertThat(constantPool).doesNotContain(value));
        }
        System.out.printf("测试证据：场景=Agent领域依赖方向，classCount=%d，forbiddenCount=%d%n",
                classes.size(), forbidden.size());
    }

    /**
     * 业务目的：应用契约不能泄漏 Spring AI、Mapper 或 Controller 类型，确保 Web、模型和测试替身可替换。
     */
    @Test
    void applicationContractsDoNotDependOnSpringAiMapperOrController() throws IOException {
        List<Path> classes = classFiles("application");
        List<String> forbidden = List.of(
                "org/springframework/ai", "com/baomidou", "Mapper", "Controller", "/infrastructure/");

        for (Path classFile : classes) {
            String constantPool = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            assertThat(forbidden).allSatisfy(value -> assertThat(constantPool).doesNotContain(value));
        }
        System.out.printf("测试证据：场景=Agent应用依赖方向，classCount=%d，forbiddenCount=%d%n",
                classes.size(), forbidden.size());
    }

    /**
     * 业务目的：Agent 新增公共类型必须留下中文职责说明，避免核心运行契约只能从实现代码反推。
     */
    @Test
    void publicAgentSourcesContainChineseJavadoc() throws IOException {
        Path sourceRoot = Path.of("src/main/java/io/github/loredock/agent");
        List<Path> sources;
        try (var stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path source : sources) {
            String text = Files.readString(source);
            assertThat(text).contains("/**");
            assertThat(text).containsPattern("[\\u4e00-\\u9fff]");
        }
        System.out.printf("测试证据：场景=Agent公共类型中文说明，sourceCount=%d%n", sources.size());
    }

    /**
     * 业务目的：Agent 工具只能依赖知识/代码读取能力，防止正常或恶意模型调用触达发布、索引或业务写入入口。
     */
    @Test
    void projectQaToolsDependOnlyOnReadCapabilitiesOutsideAgentFacts() {
        Set<String> externalTypes = Arrays.stream(
                        io.github.loredock.agent.application.ProjectQaToolService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .filter(name -> name.startsWith("io.github.loredock.knowledge")
                        || name.startsWith("io.github.loredock.code"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(externalTypes).containsExactlyInAnyOrder(
                "io.github.loredock.knowledge.application.search.KnowledgeSearchUseCase",
                "io.github.loredock.knowledge.application.search.ActiveKnowledgeSearchGenerationReader",
                "io.github.loredock.code.application.CodeSearchUseCase",
                "io.github.loredock.code.application.CodeSnippetReadUseCase",
                "io.github.loredock.code.application.ActiveCodeSnapshotQueryUseCase");
        System.out.printf("测试证据：场景=工具只读依赖，外部能力数=%d，发布/索引/写入能力数=0%n", externalTypes.size());
    }

    private List<Path> classFiles(String layer) throws IOException {
        Path root = Path.of("target/classes/io/github/loredock/agent", layer);
        try (var stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }
}

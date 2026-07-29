package io.github.loredock.project.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDomainTest {

    /**
     * 业务目的：合法项目标识需保持稳定的小写 kebab-case，并覆盖规格允许的最短与最长边界。
     */
    @Test
    void validProjectIdentifiersAreNormalizedWithinLengthBoundaries() {
        assertThat(ProjectIdentifier.of(" ab ").value()).isEqualTo("ab");
        assertThat(ProjectIdentifier.of("network-design-tool").value()).isEqualTo("network-design-tool");
        assertThat(ProjectIdentifier.of("a" + "1".repeat(63)).value()).hasSize(64);
    }

    /**
     * 业务目的：项目标识拒绝大小写漂移、非 kebab 字符及连续连字符，防止稳定业务键产生歧义。
     */
    @Test
    void invalidProjectIdentifiersAreRejected() {
        for (String value : List.of("a", "a".repeat(65), "Network", "network_tool", "-network", "network-", "network--tool", " ")) {
            assertThatThrownBy(() -> ProjectIdentifier.of(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("project identifier invalid");
        }
    }

    /**
     * 业务目的：常见 Git 风格分支名应保留大小写和层级表达，避免规范化后指向错误分支。
     */
    @Test
    void validBranchNamesPreserveCaseAndGitStyleSegments() {
        assertThat(BranchName.of(" main ").value()).isEqualTo("main");
        assertThat(BranchName.of("feature/import-export").value()).isEqualTo("feature/import-export");
        assertThat(BranchName.of("Release/V1.0").value()).isEqualTo("Release/V1.0");
        assertThat(BranchName.of("a".repeat(128)).value()).hasSize(128);
    }

    /**
     * 业务目的：危险路径样式和控制字符不得成为分支范围键，防止后续存储适配器误将名称当路径使用。
     */
    @Test
    void dangerousOrMalformedBranchNamesAreRejected() {
        for (String value : List.of(" ", "a".repeat(129), "/main", "main/", "feature//x", "feature\\x", "feature/../main", "feature/\u0001x")) {
            assertThatThrownBy(() -> BranchName.of(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("branch name invalid");
        }
    }

    /**
     * 业务目的：新项目必须立即拥有唯一默认 main 分支，后续详情省略分支时才能稳定解析范围。
     */
    @Test
    void newProjectStartsEnabledWithOnlyDefaultMainBranch() {
        Project project = Project.create(ProjectIdentifier.of("network-tool"));

        assertThat(project.status()).isEqualTo(ProjectStatus.ENABLED);
        assertThat(project.branches()).extracting(BranchName::value).containsExactly(ProjectDefaults.DEFAULT_BRANCH);
        assertThat(project.selectBranch(null).value()).isEqualTo(ProjectDefaults.DEFAULT_BRANCH);
    }

    /**
     * 业务目的：停用只改变可见状态，不删除项目标识或现有分支，确保重新启用后范围身份保持不变。
     */
    @Test
    void disablingProjectPreservesIdentityAndBranches() {
        Project enabled = Project.restore(
                ProjectIdentifier.of("network-tool"),
                ProjectStatus.ENABLED,
                List.of(BranchName.of("main"), BranchName.of("feature/import-export"))
        );

        Project disabled = enabled.withStatus(ProjectStatus.DISABLED);

        assertThat(disabled.identifier()).isEqualTo(enabled.identifier());
        assertThat(disabled.branches()).isEqualTo(enabled.branches());
        assertThat(disabled.status()).isEqualTo(ProjectStatus.DISABLED);
    }

    /**
     * 业务目的：显式请求未知分支必须失败，绝不能回退 main 后在错误范围内检索知识。
     */
    @Test
    void unknownExplicitBranchNeverFallsBackToMain() {
        Project project = Project.restore(
                ProjectIdentifier.of("network-tool"),
                ProjectStatus.ENABLED,
                List.of(BranchName.of("main"), BranchName.of("feature/import-export"))
        );

        assertThat(project.selectBranch("feature/import-export").value()).isEqualTo("feature/import-export");
        assertThatThrownBy(() -> project.selectBranch("missing"))
                .isInstanceOf(UnknownBranchException.class)
                .hasMessage("branch not found");
    }
}

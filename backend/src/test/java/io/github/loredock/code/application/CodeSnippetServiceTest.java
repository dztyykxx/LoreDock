package io.github.loredock.code.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeSnippetServiceTest {

    /**
     * 业务目的：默认从第 1 行读取最多 80 行，显式范围越过末尾要正常截短并固定同一活动 commit。
     */
    @Test
    void snippetUsesDefaultsAndTruncatesEndAtFileBoundary() {
        Fixture fixture = fixture("one\ntwo\nthree");
        CodeSnippetResponse all = fixture.service.read(new CodeSnippetQuery("alpha", null, "src/A.java", null, null));
        CodeSnippetResponse tail = fixture.service.read(new CodeSnippetQuery("alpha", null, "src/A.java", 2, 200));

        assertThat(all.startLine()).isEqualTo(1);
        assertThat(all.endLine()).isEqualTo(3);
        assertThat(all.content()).isEqualTo("one\ntwo\nthree");
        assertThat(all.commit()).isEqualTo("abcdef1");
        assertThat(tail.content()).isEqualTo("two\nthree");
        assertThat(tail.endLine()).isEqualTo(3);
    }

    /**
     * 业务目的：起始行超过文件末尾必须返回稳定 416，而非法路径/行数必须在索引访问前返回 400。
     */
    @Test
    void outOfRangeAndInvalidLogicalInputsUseDistinctFailures() {
        Fixture fixture = fixture("one\ntwo");
        assertThatThrownBy(() -> fixture.service.read(
                new CodeSnippetQuery("alpha", null, "src/A.java", 3, 1)))
                .isInstanceOf(CodeSnippetRangeInvalidException.class);
        assertThatThrownBy(() -> fixture.service.read(
                new CodeSnippetQuery("alpha", null, "../secret", 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.service.read(
                new CodeSnippetQuery("alpha", null, "src/A.java", 0, 201)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：不存在、被忽略、敏感或跨范围路径在活动索引中都统一表现为 404，不能从原对象旁路读取。
     */
    @Test
    void absentStoredPathIsUniformlyNotFound() {
        Fixture fixture = fixture(null);
        assertThatThrownBy(() -> fixture.service.read(
                new CodeSnippetQuery("alpha", null, ".env", 1, 1)))
                .isInstanceOf(CodeFileNotFoundException.class);
    }

    private Fixture fixture(String content) {
        ActiveCodeSnapshotDescriptor active = new ActiveCodeSnapshotDescriptor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "abcdef1",
                Instant.parse("2026-07-30T08:00:00Z"), 1, CodeSnapshotChangeHint.INITIAL);
        ActiveCodeSnapshotResolver resolver = mock(ActiveCodeSnapshotResolver.class);
        when(resolver.resolve("alpha", null)).thenReturn(new ResolvedCodeSnapshotScope(
                "alpha", active.projectId(), "main", active.branchId(), Optional.of(active)));
        CodeIndexSnippetPort index = mock(CodeIndexSnippetPort.class);
        when(index.read(active, content == null ? ".env" : "src/A.java")).thenReturn(Optional.ofNullable(content));
        return new Fixture(new CodeSnippetService(resolver, index));
    }

    private record Fixture(CodeSnippetService service) {
    }
}

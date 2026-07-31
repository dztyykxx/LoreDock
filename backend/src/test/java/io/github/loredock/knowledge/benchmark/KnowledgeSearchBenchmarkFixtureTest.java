package io.github.loredock.knowledge.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeSearchBenchmarkFixtureTest {

    /**
     * 业务目的：正式检索前必须证明问题数量、类型、三种模式、人工正确来源和无答案理由完整，
     * 防止通过删除失败题或让待评估系统反向生成标注来提高分数。
     */
    @Test
    void validFixtureHasRequiredCompositionAndIndependentAnnotations() {
        var fixture = KnowledgeSearchBenchmarkFixture.load();

        new KnowledgeSearchBenchmarkFixtureValidator().validate(fixture);

        long answerable = fixture.questions().stream().filter(KnowledgeSearchBenchmarkFixture.Question::hasAnswer)
                .count();
        assertThat(fixture.manifest().reviewedByHuman()).isTrue();
        assertThat(fixture.questions()).hasSize(18);
        assertThat(answerable).isEqualTo(14);
        System.out.printf("测试证据：场景=基准结构校验，版本=%s，文档数=%d，问题数=%d，有答案=%d，无答案=%d%n",
                fixture.manifest().benchmarkVersion(), fixture.manifest().documents().size(),
                fixture.questions().size(), answerable, fixture.questions().size() - answerable);
    }

    /**
     * 业务目的：所有可提交材料必须在运行检索前通过常见凭据、私钥、内部域名和私网地址扫描，
     * 防止基准报告把真实内部信息带入公开仓库。
     */
    @Test
    void validFixtureContainsOnlyReviewedSimulatedContent() {
        var fixture = KnowledgeSearchBenchmarkFixture.load();

        new KnowledgeSearchBenchmarkFixtureValidator().validate(fixture);

        assertThat(fixture.manifest().reviewNote()).contains("人工").contains("独立确定");
        assertThat(fixture.manifest().documents())
                .allMatch(document -> KnowledgeSearchBenchmarkFixture.readText(document.file()).contains("# "));
        System.out.printf("测试证据：场景=基准敏感模式校验，审查状态=%s，扫描文档数=%d，问题数=%d%n",
                fixture.manifest().reviewedByHuman(), fixture.manifest().documents().size(),
                fixture.questions().size());
    }
}

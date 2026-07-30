package io.github.loredock.knowledge.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDocumentValueObjectTest {

    /**
     * 业务目的：标题按 Unicode 码点而非 UTF-16 单元计数并统一 NFC，防止中文和 emoji 被错误截断或等价文本产生不同事实。
     */
    @Test
    void titleNormalizesUnicodeAndCountsCodePoints() {
        String twoHundredEmoji = "😀".repeat(KnowledgeDocumentLimits.TITLE_MAX_CODE_POINTS);

        assertThat(new DocumentTitle("  Cafe\u0301  ").value()).isEqualTo("Café");
        assertThat(new DocumentTitle(twoHundredEmoji).value()).isEqualTo(twoHundredEmoji);
        assertThatThrownBy(() -> new DocumentTitle(twoHundredEmoji + "😀"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：正文只校验非空和公布上限而不解释或改写标记，防止纯文本、Markdown 或 HTML 样例在保存时改变语义。
     */
    @Test
    void bodyPreservesUntrustedTextAndRejectsInvalidLength() {
        String text = "  # 标题\n<script>alert('不执行')</script>  ";

        assertThat(new DocumentBody(text).value()).isEqualTo(text);
        assertThatThrownBy(() -> new DocumentBody(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentBody(
                "a".repeat(KnowledgeDocumentLimits.BODY_MAX_CODE_POINTS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：目录是逻辑分类路径，绝对路径、空段、点段、反斜杠和控制字符必须拒绝，防止未来导入逻辑把它误作文件系统路径。
     */
    @ParameterizedTest
    @ValueSource(strings = {"/业务", "业务/", "业务//规则", "业务/./规则", "业务/../规则", "业务\\规则", "业务\u0000规则"})
    void directoryRejectsFilesystemAndAmbiguousPaths(String value) {
        assertThatThrownBy(() -> new DocumentDirectory(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：合法目录需要保留层级并统一 Unicode，根目录保持空字符串，防止同一目录产生多个规范值。
     */
    @Test
    void directoryNormalizesUnicodeAndKeepsRoot() {
        assertThat(new DocumentDirectory("").value()).isEmpty();
        assertThat(new DocumentDirectory("  Cafe\u0301/规则  ").value()).isEqualTo("Café/规则");
    }

    /**
     * 业务目的：标签显示值统一 NFC 和首尾空白，去重值固定为大小写无关形式，防止数据库与 API 对同一标签解释不同。
     */
    @Test
    void tagNormalizesDisplayAndDuplicateKey() {
        DocumentTag tag = new DocumentTag("  Cafe\u0301  ", null);

        assertThat(tag.displayName()).isEqualTo("Café");
        assertThat(tag.normalizedName()).isEqualTo("café");
    }

    /**
     * 业务目的：规范化后重复的标签必须作为无效请求拒绝，防止服务端静默丢弃用户输入并掩盖表单错误。
     */
    @Test
    void duplicateTagsAreRejectedAfterUnicodeAndCaseNormalization() {
        assertThatThrownBy(() -> new DocumentTags(List.of(
                new DocumentTag("API", null),
                new DocumentTag(" api ", null)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：Wiki 来源必须有合法 HTTP(S) 地址，防止无法追溯或使用脚本协议的来源进入正式知识。
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.test/wiki", "not-a-url"})
    void wikiSourceRequiresSafeHttpUrl(String wikiUrl) {
        assertThatThrownBy(() -> new DocumentSource(DocumentSourceType.WIKI, wikiUrl, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：上传来源必须记录原文件名，防止导入草稿失去最基本的来源追溯信息。
     */
    @ParameterizedTest
    @NullAndEmptySource
    void uploadSourceRequiresOriginalFilename(String originalFilename) {
        assertThatThrownBy(() -> new DocumentSource(DocumentSourceType.UPLOAD, null, originalFilename, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：GLOBAL、PROJECT、BRANCH 的 UUID 组合必须互斥且完整，防止范围字段被静默丢弃或回退。
     */
    @Test
    void scopeRejectsMissingAndResidualIdentifiers() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        assertThatThrownBy(() -> new KnowledgeScope(KnowledgeScopeType.GLOBAL, projectId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeScope(KnowledgeScopeType.PROJECT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeScope(KnowledgeScopeType.PROJECT, projectId, branchId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeScope(KnowledgeScopeType.BRANCH, projectId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 业务目的：分支范围必须同时保存项目和分支稳定 UUID，供应用层继续校验分支归属，禁止用名称拼接伪造范围。
     */
    @Test
    void branchScopeKeepsProjectAndBranchStableIdentifiers() {
        UUID projectId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        KnowledgeScope scope = new KnowledgeScope(KnowledgeScopeType.BRANCH, projectId, branchId);

        assertThat(scope.projectId()).isEqualTo(projectId);
        assertThat(scope.branchId()).isEqualTo(branchId);
    }
}

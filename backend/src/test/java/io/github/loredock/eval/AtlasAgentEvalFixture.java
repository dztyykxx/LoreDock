package io.github.loredock.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atlas Agent 评估数据集加载器。
 *
 * <p>直接读取仓库 <code>docs/quality/atlas-agent-eval-tests</code> 与
 * <code>docs/quality/atlas-eval-documents</code> 两个目录，保持测试数据只有一份来源；
 * 数据目录可通过系统属性 {@value #DATA_DIR_PROPERTY} 覆盖，默认从当前工作目录向上查找仓库根。</p>
 *
 * <p>加载后立即执行构造要求中的最小质量检查（用例数量、类型分布、文档 ID 反查、
 * 引用文档存在、参考回答完整），任何一项不满足都会阻止评估运行，防止用不完整数据得出指标。</p>
 */
public final class AtlasAgentEvalFixture {

    /** 指向包含 atlas-agent-eval-tests 与 atlas-eval-documents 的目录（即 docs/quality）的系统属性。 */
    public static final String DATA_DIR_PROPERTY = "loredock.agent-eval.data-dir";

    private static final String TESTS_DIR = "atlas-agent-eval-tests";
    private static final String DOCUMENTS_DIR = "atlas-eval-documents";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AtlasAgentEvalFixture() {
    }

    /** @return 从系统属性或仓库根解析数据目录并加载、校验后的评估数据集 */
    public static EvalData load() {
        return load(locateDataDir());
    }

    /** @param dataDir 包含两个数据集目录的根目录 @return 加载并校验后的评估数据集 */
    public static EvalData load(Path dataDir) {
        Path testsRoot = requireDirectory(dataDir.resolve(TESTS_DIR));
        Path documentsRoot = requireDirectory(dataDir.resolve(DOCUMENTS_DIR));
        Manifest manifest = readJson(testsRoot.resolve("manifest.json"), Manifest.class);
        List<QaCase> qaCases = readJson(testsRoot.resolve("qa-cases.json"),
                new com.fasterxml.jackson.core.type.TypeReference<List<QaCase>>() {
                });
        List<CurationCase> curationCases = readJson(testsRoot.resolve("curation-cases.json"),
                new com.fasterxml.jackson.core.type.TypeReference<List<CurationCase>>() {
                });
        DocumentManifest documentManifest = readJson(documentsRoot.resolve("manifest.json"), DocumentManifest.class);
        List<DocumentSpec> documents = new ArrayList<>();
        for (DocumentEntry entry : documentManifest.documents()) {
            Long mappedId = manifest.documentIdMappings().get(entry.documentId());
            if (mappedId == null) {
                continue;
            }
            documents.add(new DocumentSpec(
                    entry.documentId(), mappedId, entry.title(), entry.directory(), entry.status(),
                    entry.scope(), entry.project(), entry.file(),
                    readText(documentsRoot.resolve(entry.file()), entry.documentId())));
        }
        EvalData data = new EvalData(manifest, qaCases, curationCases, List.copyOf(documents));
        validate(data);
        return data;
    }

    /**
     * 执行《Agent 评估测试数据构造要求》第 10 节的最小质量检查，集中报告全部违规项。
     *
     * @param data 已加载的评估数据集
     * @throws IllegalStateException 任何质量检查不满足
     */
    public static void validate(EvalData data) {
        List<String> violations = new ArrayList<>();
        Manifest manifest = data.manifest();
        if (manifest.qaCaseCount() != data.qaCases().size()) {
            violations.add("manifest qaCaseCount=" + manifest.qaCaseCount()
                    + " 与实际用例数 " + data.qaCases().size() + " 不一致");
        }
        if (manifest.curationCaseCount() != data.curationCases().size()) {
            violations.add("manifest curationCaseCount=" + manifest.curationCaseCount()
                    + " 与实际用例数 " + data.curationCases().size() + " 不一致");
        }
        Map<String, Long> mappings = manifest.documentIdMappings();
        Map<Long, DocumentSpec> byId = new LinkedHashMap<>();
        for (DocumentSpec document : data.documents()) {
            byId.put(document.documentId(), document);
        }
        // 反向校验：manifest 每个映射业务键都必须能在基础文档清单中解析出正文。
        for (Map.Entry<String, Long> mapping : mappings.entrySet()) {
            if (!byId.containsKey(mapping.getValue())) {
                violations.add("映射业务键 " + mapping.getKey() + "=" + mapping.getValue() + " 在基础文档中缺失");
            }
        }
        for (QaCase qaCase : data.qaCases()) {
            if (qaCase.caseId() == null || qaCase.caseId().isBlank()) {
                violations.add("存在缺少 caseId 的 QA 用例");
            }
            if (qaCase.caseType() == null || qaCase.caseType().isBlank()) {
                violations.add(qaCase.caseId() + " 缺少 caseType");
            }
            if (qaCase.input() == null || qaCase.expected() == null) {
                violations.add(qaCase.caseId() + " 缺少 input 或 expected");
                continue;
            }
            if (qaCase.input().question() == null || qaCase.input().question().isBlank()) {
                violations.add(qaCase.caseId() + " 缺少问题正文");
            }
            if (qaCase.expected().resultType() == null || qaCase.expected().resultType().isBlank()) {
                violations.add(qaCase.caseId() + " 缺少 expected.resultType");
            }
            for (Long documentId : qaCase.expected().documentIds()) {
                if (!byId.containsKey(documentId)) {
                    violations.add(qaCase.caseId() + " 引用未加载文档 ID=" + documentId);
                }
            }
            if ("ANSWER".equals(qaCase.expected().resultType())) {
                if (qaCase.expected().documentIds().isEmpty()) {
                    violations.add(qaCase.caseId() + " 可回答用例缺少 expected.documentIds");
                }
                if (qaCase.expected().resultText() == null || qaCase.expected().resultText().isBlank()) {
                    violations.add(qaCase.caseId() + " 可回答用例缺少参考回答");
                }
            } else if ("REFUSAL".equals(qaCase.expected().resultType())) {
                if (qaCase.expected().refusalReason() == null || qaCase.expected().refusalReason().isBlank()) {
                    violations.add(qaCase.caseId() + " 拒答用例缺少 expected.refusalReason");
                } else if ("INSUFFICIENT_EVIDENCE".equals(qaCase.expected().refusalReason())
                        && !qaCase.expected().documentIds().isEmpty()) {
                    // 证据不足拒答不允许携带来源；来源冲突拒答必须列出冲突文档，二者口径不同。
                    violations.add(qaCase.caseId() + " 证据不足拒答用例不应携带 expected.documentIds");
                } else if ("SOURCE_CONFLICT".equals(qaCase.expected().refusalReason())
                        && qaCase.expected().documentIds().isEmpty()) {
                    violations.add(qaCase.caseId() + " 来源冲突拒答用例必须列出冲突文档");
                }
            }
        }
        Set<Long> curatedDraftIds = new java.util.HashSet<>();
        for (CurationCase curationCase : data.curationCases()) {
            if (curationCase.caseId() == null || curationCase.caseId().isBlank()) {
                violations.add("存在缺少 caseId 的知识整理用例");
            }
            if (curationCase.input() == null || curationCase.expected() == null) {
                violations.add(curationCase.caseId() + " 缺少 input 或 expected");
                continue;
            }
            Long draftId = curationCase.input().selectedDraftId();
            if (draftId == null) {
                violations.add(curationCase.caseId() + " 缺少 selectedDraftId");
            } else if (!byId.containsKey(draftId)) {
                violations.add(curationCase.caseId() + " selectedDraftId=" + draftId + " 不在加载文档中");
            } else if (byId.get(draftId).status() == null || !"DRAFT".equals(byId.get(draftId).status())) {
                violations.add(curationCase.caseId() + " selectedDraftId=" + draftId + " 不是 DRAFT 状态");
            } else if (!curatedDraftIds.add(draftId)) {
                violations.add(curationCase.caseId() + " 草稿 " + draftId + " 被多条用例复用");
            }
            if (curationCase.input().goal() == null || curationCase.input().goal().isBlank()) {
                violations.add(curationCase.caseId() + " 缺少 goal");
            }
            if (curationCase.expected().finalResponse() == null || curationCase.expected().finalResponse().isBlank()) {
                violations.add(curationCase.caseId() + " 缺少完整参考最终回答");
            }
            for (Long documentId : curationCase.expected().relatedDocumentIds()) {
                if (!byId.containsKey(documentId)) {
                    violations.add(curationCase.caseId() + " 关联文档 ID=" + documentId + " 不在加载文档中");
                }
            }
        }
        if (mappings.isEmpty()) {
            violations.add("manifest documentIdMappings 为空");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Atlas Agent 评估数据最小质量检查失败：\n  - " + String.join("\n  - ", violations));
        }
    }

    /**
     * 解析数据根目录：优先系统属性 {@value #DATA_DIR_PROPERTY}，否则从当前工作目录逐级向上
     * 查找同时包含两个数据集目录的 docs/quality，兼容从 backend 或仓库根运行 Maven 的情况。
     *
     * @return 数据根目录（包含 atlas-agent-eval-tests 与 atlas-eval-documents）
     */
    static Path locateDataDir() {
        String configured = System.getProperty(DATA_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            requireDirectory(directory.resolve(TESTS_DIR));
            requireDirectory(directory.resolve(DOCUMENTS_DIR));
            return directory;
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("docs/quality");
            if (Files.isRegularFile(candidate.resolve(TESTS_DIR).resolve("manifest.json"))
                    && Files.isRegularFile(candidate.resolve(DOCUMENTS_DIR).resolve("manifest.json"))) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位 Atlas Agent 评估数据目录：请使用 -D" + DATA_DIR_PROPERTY
                + "=docs/quality 或从仓库内运行测试");
    }

    private static Path requireDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("评估数据目录不存在：" + path);
        }
        return path;
    }

    private static String readText(Path file, String businessId) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("评估文档读取失败：" + businessId + " -> " + file, exception);
        }
    }

    private static <T> T readJson(Path file, Class<T> type) {
        try {
            return JSON.readValue(file.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("评估数据 JSON 读取失败：" + file, exception);
        }
    }

    private static <T> T readJson(Path file, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return JSON.readValue(file.toFile(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("评估数据 JSON 读取失败：" + file, exception);
        }
    }

    /** 完整评估数据集：manifest、QA 用例、知识整理用例与全部基础文档。 */
    public record EvalData(
            Manifest manifest,
            List<QaCase> qaCases,
            List<CurationCase> curationCases,
            List<DocumentSpec> documents
    ) {
        public EvalData {
            qaCases = qaCases == null ? List.of() : List.copyOf(qaCases);
            curationCases = curationCases == null ? List.of() : List.copyOf(curationCases);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        /** @param documentId 固定 Long ID @return 对应基础文档；不存在时返回 null */
        public DocumentSpec documentOf(Long documentId) {
            return documents.stream().filter(document -> documentId.equals(document.documentId()))
                    .findFirst().orElse(null);
        }
    }

    /** 数据集 manifest：只保存运行和评分真正需要的字段。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(
            String datasetVersion,
            String projectIdentifier,
            int qaCaseCount,
            int curationCaseCount,
            boolean reviewedByHuman,
            Map<String, Long> documentIdMappings
    ) {
        public Manifest {
            documentIdMappings = documentIdMappings == null
                    ? Map.of() : Map.copyOf(documentIdMappings);
        }
    }

    /** QA 测试用例：输入问题与预期结果保存在同一 JSON 对象。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QaCase(String caseId, String caseType, QaInput input, QaExpected expected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QaInput(String projectIdentifier, String branch, String question) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QaExpected(
            String resultType,
            String refusalReason,
            String resultText,
            List<Long> documentIds
    ) {
        public QaExpected {
            documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        }
    }

    /** 知识整理测试用例：只选择一篇完整草稿。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurationCase(String caseId, CurationInput input, CurationExpected expected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurationInput(String projectIdentifier, Long selectedDraftId, String goal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurationExpected(
            String issueType,
            List<Long> relatedDocumentIds,
            String action,
            String finalResponse,
            WorkspaceExpectation workspace,
            List<String> forbiddenDraftFacts
    ) {
        public CurationExpected {
            relatedDocumentIds = relatedDocumentIds == null ? List.of() : List.copyOf(relatedDocumentIds);
            forbiddenDraftFacts = forbiddenDraftFacts == null ? List.of() : List.copyOf(forbiddenDraftFacts);
        }
    }

    /** 预期工作区处置；为 null 表示本轮不应产生工作文档。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkspaceExpectation(String operation, Long baselineDocumentId) {
    }

    /** 基础文档规格：businessId 为业务键，documentId 为 manifest 映射的固定 Long ID（未映射时为 null）。 */
    public record DocumentSpec(
            String businessId,
            Long documentId,
            String title,
            String directory,
            String status,
            String scope,
            String project,
            String file,
            String markdown
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentManifest(String datasetVersion, String project, List<DocumentEntry> documents) {
        DocumentManifest {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentEntry(
            String documentId, String title, String directory, String status, String scope,
            String project, String updatedAt, String file
    ) {
    }
}

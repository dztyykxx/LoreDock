package io.github.loredock.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 后端 MVC 结构守卫。直接检查项目源码，避免为少量稳定规则引入额外架构测试框架。
 *
 * <p>这些规则保护功能模块内部的 MVC 目录、Mapper 访问边界和少量真实接口。测试失败会输出
 * 具体文件与依赖，便于逐模块迁移，而不是只给出无法定位的数量。</p>
 */
class BackendMvcArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/io/github/loredock");
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "agent", "auth", "code", "feedback", "identity", "job",
            "knowledge", "knowledgegap", "project", "qa", "storage");
    private static final Set<String> MODULE_PACKAGES = Set.of(
            "api", "controller", "service", "mapper", "model", "config",
            "exception", "scheduler", "converter", "skill");
    private static final Set<String> PLATFORM_PACKAGES = Set.of("config", "persistence", "time", "web");
    private static final Set<String> ALLOWED_BOUNDARY_INTERFACES = Set.of(
            "AgentDefinitionProvider", "AgentRuntime", "JobExecutionContext", "JobHandler", "ObjectStorage",
            "WebQaSseSink");
    /** 阶段六渐进迁移的存量跨模块非 api 引用清单；只允许随模块重构清空，不允许新增条目。 */
    private static final List<String> KNOWN_CROSS_MODULE_VIOLATIONS = List.of(
                "code/model/response/CodeSnapshotJobResponse.java -> import io.github.loredock.job.model.enums.JobStatus",
                "code/model/result/CodeSnapshotJobView.java -> import io.github.loredock.job.model.enums.JobStatus",
                "code/service/AdminCodeSnapshotQueryService.java -> import io.github.loredock.job.model.snapshot.JobSnapshot",
                "code/service/AdminCodeSnapshotQueryService.java -> import io.github.loredock.job.service.PersistentBackgroundJobService",
                "code/service/CodeSnapshotBuildJobHandler.java -> import io.github.loredock.job.service.JobExecutionContext",
                "code/service/CodeSnapshotBuildJobHandler.java -> import io.github.loredock.job.service.JobHandler",
                "code/service/CodeSnapshotGenerationBuilder.java -> import io.github.loredock.job.service.JobExecutionContext",
                "code/service/CodeSnapshotRecoveryService.java -> import io.github.loredock.job.service.PersistentBackgroundJobService",
                "code/service/CodeSnapshotRegistrationService.java -> import io.github.loredock.job.model.request.JobRequest",
                "code/service/CodeSnapshotRegistrationService.java -> import io.github.loredock.job.model.snapshot.JobSnapshot",
                "code/service/CodeSnapshotRegistrationService.java -> import io.github.loredock.job.service.PersistentBackgroundJobService",
                "code/service/CodeSnapshotReindexJobHandler.java -> import io.github.loredock.job.service.JobExecutionContext",
                "code/service/CodeSnapshotReindexJobHandler.java -> import io.github.loredock.job.service.JobHandler",
                "code/service/CodeSnapshotUploadService.java -> import io.github.loredock.storage.model.result.ObjectMetadata",
                "code/service/CodeSnapshotUploadService.java -> import io.github.loredock.storage.model.result.StoredObject",
                "code/service/CodeSnapshotUploadService.java -> import io.github.loredock.storage.service.ObjectStorage",
                "code/service/archive/CommonsCompressCodeArchiveReader.java -> import io.github.loredock.storage.service.ObjectStorage",
                "code/service/storage/ObjectStorageCodeSnapshotCompensation.java -> import io.github.loredock.storage.service.ObjectStorage",
                "feedback/controller/AdminKnowledgeGapController.java -> import io.github.loredock.auth.model.AuthenticatedActor",
                "feedback/controller/AdminKnowledgeGapController.java -> import io.github.loredock.auth.service.SessionService",
                "feedback/controller/KnowledgeGapController.java -> import io.github.loredock.auth.model.AuthenticatedActor",
                "feedback/controller/KnowledgeGapController.java -> import io.github.loredock.auth.service.SessionService",
                "feedback/model/response/KnowledgeGapFeedbackResponse.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "feedback/model/response/KnowledgeGapFeedbackResponse.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "feedback/model/response/KnowledgeGapFeedbackResponse.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "feedback/model/result/KnowledgeGapFeedbackRecord.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "feedback/model/result/KnowledgeGapFeedbackRecord.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "feedback/model/result/KnowledgeGapFeedbackRecord.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.exception.WebQaQuestionNotFoundException",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.model.command.QueryWebQaDetailCommand",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.model.enums.WebQaMessageRole",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.model.result.WebQaMessageRecord",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot",
                "feedback/service/CreateKnowledgeGapService.java -> import io.github.loredock.qa.service.QueryWebQaQuestionService",
                "feedback/service/KnowledgeGapDataService.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "feedback/service/KnowledgeGapDataService.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "feedback/service/KnowledgeGapDataService.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "knowledge/model/response/KnowledgeIndexJobResponse.java -> import io.github.loredock.job.model.enums.JobStatus",
                "knowledge/model/result/KnowledgeIndexJobView.java -> import io.github.loredock.job.model.enums.JobStatus",
                "knowledge/service/KnowledgeIndexJobService.java -> import io.github.loredock.job.model.enums.JobStatus",
                "knowledge/service/KnowledgeIndexJobService.java -> import io.github.loredock.job.model.request.JobRequest",
                "knowledge/service/KnowledgeIndexJobService.java -> import io.github.loredock.job.model.snapshot.JobSnapshot",
                "knowledge/service/KnowledgeIndexJobService.java -> import io.github.loredock.job.service.PersistentBackgroundJobService",
                "knowledge/service/importing/KnowledgeDocumentImportService.java -> import io.github.loredock.storage.model.result.ObjectMetadata",
                "knowledge/service/importing/KnowledgeDocumentImportService.java -> import io.github.loredock.storage.model.result.StoredObject",
                "knowledge/service/importing/KnowledgeDocumentImportService.java -> import io.github.loredock.storage.service.ObjectStorage",
                "knowledge/service/importing/KnowledgeZipArchiveService.java -> import io.github.loredock.storage.service.ObjectStorage",
                "knowledge/service/importing/ObjectStorageImportCompensation.java -> import io.github.loredock.storage.service.ObjectStorage",
                "knowledge/service/indexing/KnowledgeReindexJobHandler.java -> import io.github.loredock.job.service.JobExecutionContext",
                "knowledge/service/indexing/KnowledgeReindexJobHandler.java -> import io.github.loredock.job.service.JobHandler",
                "qa/controller/WebQaController.java -> import io.github.loredock.agent.service.AgentRunQueryService",
                "qa/controller/WebQaController.java -> import io.github.loredock.auth.model.AuthenticatedActor",
                "qa/controller/WebQaController.java -> import io.github.loredock.auth.service.SessionService",
                "qa/controller/WebQaSseController.java -> import io.github.loredock.auth.model.AuthenticatedActor",
                "qa/controller/WebQaSseController.java -> import io.github.loredock.auth.service.SessionService",
                "qa/converter/WebQaFailureMessageMapper.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/converter/WebQaFailureMessageMapper.java -> import io.github.loredock.agent.model.enums.AgentRunStatus",
                "qa/converter/WebQaHttpMapper.java -> import io.github.loredock.agent.model.snapshot.AgentCitationSnapshot",
                "qa/converter/WebQaHttpMapper.java -> import io.github.loredock.agent.model.snapshot.EvidenceSourceMetadata",
                "qa/converter/WebQaSseEventMapper.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/converter/WebQaSseEventMapper.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/converter/WebQaSseEventMapper.java -> import io.github.loredock.agent.model.snapshot.AgentEventSnapshot",
                "qa/model/enums/WebQaTrustState.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/model/enums/WebQaTrustState.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "qa/model/enums/WebQaTrustState.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/model/enums/WebQaTrustState.java -> import io.github.loredock.agent.model.enums.AgentRunStatus",
                "qa/model/request/WebQaSseStreamRequest.java -> import io.github.loredock.auth.service.SessionService",
                "qa/model/response/WebQaCitationResponse.java -> import io.github.loredock.agent.model.enums.EvidenceSourceType",
                "qa/model/response/WebQaMessageResponse.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "qa/model/response/WebQaMessageResponse.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/model/response/WebQaQuestionResponse.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/model/response/WebQaQuestionResponse.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "qa/model/response/WebQaQuestionResponse.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/model/response/WebQaQuestionResponse.java -> import io.github.loredock.agent.model.enums.AgentRunStatus",
                "qa/model/response/WebQaQuestionResponse.java -> import io.github.loredock.agent.model.enums.AnswerBasis",
                "qa/model/result/WebQaMessageRecord.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "qa/model/result/WebQaMessageRecord.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/model/result/WebQaStreamTarget.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "qa/model/snapshot/WebQaQuestionSnapshot.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "qa/model/snapshot/WebQaSseEventV1.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/model/snapshot/WebQaSseEventV1.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.exception.AgentRequestException",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.model.command.StartProjectQaRunCommand",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.model.enums.AgentErrorCode",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.service.AgentRunQueryService",
                "qa/service/CreateWebQaQuestionService.java -> import io.github.loredock.agent.service.StartProjectQaRunService",
                "qa/service/DefaultWebQaAssistantMessageMaterializer.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/service/DefaultWebQaAssistantMessageMaterializer.java -> import io.github.loredock.agent.model.enums.AgentRunStatus",
                "qa/service/DefaultWebQaAssistantMessageMaterializer.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "qa/service/QueryWebQaQuestionService.java -> import io.github.loredock.agent.exception.AgentRunNotFoundException",
                "qa/service/QueryWebQaQuestionService.java -> import io.github.loredock.agent.model.snapshot.AgentRunSnapshot",
                "qa/service/QueryWebQaQuestionService.java -> import io.github.loredock.agent.service.AgentRunQueryService",
                "qa/service/WebQaMessageDataService.java -> import io.github.loredock.agent.model.enums.AgentRefusalReason",
                "qa/service/WebQaMessageDataService.java -> import io.github.loredock.agent.model.enums.AgentResultType",
                "qa/service/WebQaSseService.java -> import io.github.loredock.agent.model.snapshot.AgentEventSnapshot",
                "qa/service/WebQaSseService.java -> import io.github.loredock.agent.service.AgentEventService",
                "qa/service/WebQaSseService.java -> import io.github.loredock.agent.service.AgentRunQueryService",
                "qa/service/WebQaSseService.java -> import io.github.loredock.auth.service.SessionService"
    );

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?m)^import io\\.github\\.loredock\\.([a-z0-9]+)(?:\\.([A-Za-z0-9_.]+))?;");
    private static final Pattern PUBLIC_INTERFACE_PATTERN = Pattern.compile(
            "(?m)^public interface ([A-Za-z0-9_]+)");

    /**
     * 业务目的：业务能力只能位于约定的 MVC 主目录和少量职责明确的辅助目录，防止旧分层再次扩散。
     */
    @Test
    void businessModulesOnlyUseMvcPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 2 || !BUSINESS_MODULES.contains(relative.getName(0).toString())) {
                continue;
            }
            String layer = relative.getName(1).toString();
            if (!MODULE_PACKAGES.contains(layer)) {
                violations.add(relative + " -> 未允许的模块目录 " + layer);
            }
        }

        printEvidence("业务模块非 MVC 类", violations);
        assertThat(violations).as("业务模块必须严格使用 MVC 主目录或明确允许的辅助目录").isEmpty();
    }

    /**
     * 业务目的：Mapper 目录只保存 MyBatis-Plus Mapper 接口，防止实体和仓储实现继续伪装成 Mapper 层。
     */
    @Test
    void mapperPackagesOnlyContainMapperInterfaces() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 2
                    || !BUSINESS_MODULES.contains(relative.getName(0).toString())) {
                continue;
            }
            String path = normalizedPath(source);
            if (!path.contains("/mapper/")) {
                continue;
            }
            String content = Files.readString(source);
            String fileName = source.getFileName().toString();
            Matcher matcher = PUBLIC_INTERFACE_PATTERN.matcher(content);
            if (!fileName.endsWith("Mapper.java") || !matcher.find()
                    || !matcher.group(1).endsWith("Mapper")) {
                violations.add(SOURCE_ROOT.relativize(source) + " -> Mapper 目录只能放 *Mapper 接口");
            }
        }

        printEvidence("Mapper 职责越界", violations);
        assertThat(violations).as("Mapper 目录只能保存 MyBatis-Plus Mapper 接口").isEmpty();
    }

    /**
     * 业务目的：Controller 目录只保存 HTTP 入口，避免请求响应模型和辅助实现重新堆入入口层。
     */
    @Test
    void controllerPackagesOnlyContainControllers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            String path = normalizedPath(source);
            if (path.contains("/controller/") && !source.getFileName().toString().endsWith("Controller.java")) {
                violations.add(SOURCE_ROOT.relativize(source) + " -> Controller 目录只能放 *Controller");
            }
        }

        printEvidence("Controller 职责越界", violations);
        assertThat(violations).as("Controller 目录只能保存 Controller").isEmpty();
    }

    /**
     * 业务目的：Service 只承载业务流程，数据载体、配置、异常和调度实现必须回到语义明确的目录。
     */
    @Test
    void servicePackagesDoNotContainDataOrInfrastructureTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        Pattern misplacedName = Pattern.compile(
                ".*(?:Request|Response|Command|Result|Snapshot|Record|View|Entity|Row|Dto|DTO|Enum|Properties|Configuration|Exception|Scheduler|Recovery)\\.java$");
        for (Path source : javaSources()) {
            String path = normalizedPath(source);
            if (!path.contains("/service/")) {
                continue;
            }
            String content = Files.readString(source);
            String fileName = source.getFileName().toString();
            if (misplacedName.matcher(fileName).matches()
                    || content.contains("@TableName(")
                    || Pattern.compile("(?m)^public (?:record|enum) ").matcher(content).find()) {
                violations.add(SOURCE_ROOT.relativize(source) + " -> Service 目录只保存业务服务");
            }
        }

        printEvidence("Service 职责越界", violations);
        assertThat(violations).as("Service 不得保存数据载体、配置、异常或调度实现").isEmpty();
    }

    /**
     * 业务目的：常见数据模型按语义归档，避免所有 record 和实体重新堆入无边界的 model 根目录。
     */
    @Test
    void dataModelsUseSemanticModelSubpackages() throws IOException {
        Map<String, String> suffixPackages = Map.ofEntries(
                Map.entry("Entity.java", "/model/entity/"),
                Map.entry("Row.java", "/model/entity/"),
                Map.entry("Request.java", "/model/request/"),
                Map.entry("Response.java", "/model/response/"),
                Map.entry("Command.java", "/model/command/"),
                Map.entry("Result.java", "/model/result/"),
                Map.entry("Snapshot.java", "/model/snapshot/"));
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 2
                    || !BUSINESS_MODULES.contains(relative.getName(0).toString())) {
                continue;
            }
            String path = normalizedPath(source);
            String fileName = source.getFileName().toString();
            for (Map.Entry<String, String> rule : suffixPackages.entrySet()) {
                boolean toolRequest = fileName.endsWith("ToolRequest.java") && path.contains("/model/tool/");
                if (fileName.endsWith(rule.getKey()) && !path.contains(rule.getValue()) && !toolRequest) {
                    violations.add(SOURCE_ROOT.relativize(source) + " -> 应位于 " + rule.getValue());
                    break;
                }
            }
            String content = Files.readString(source);
            if (Pattern.compile("(?m)^public enum ").matcher(content).find()
                    && !path.contains("/model/enums/")) {
                violations.add(SOURCE_ROOT.relativize(source) + " -> 公共枚举应位于 /model/enums/");
            }
        }

        printEvidence("模型语义目录错误", violations);
        assertThat(violations).as("数据模型必须进入语义明确的 model 子包").isEmpty();
    }

    /**
     * 业务目的：Controller 只能编排 HTTP 契约并调用 Service，禁止直接操作 Mapper 绕过业务规则。
     */
    @Test
    void controllersDoNotAccessMappers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            String path = normalizedPath(source);
            if (!path.contains("/controller/") && !path.contains("/infrastructure/web/")) {
                continue;
            }
            String content = Files.readString(source);
            if (content.matches("(?s).*import io\\.github\\.loredock\\.[^;]+\\.mapper\\.[^;]+;.*")) {
                violations.add(SOURCE_ROOT.relativize(source).toString());
            }
        }

        printEvidence("Controller 直接访问 Mapper", violations);
        assertThat(violations).as("Controller 不得直接访问 Mapper").isEmpty();
    }

    /**
     * 业务目的：Service 跨模块必须调用对方 Service，禁止耦合其他模块表结构。
     */
    @Test
    void servicesDoNotAccessOtherModuleMappers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 3) {
                continue;
            }
            String module = normalizeModule(relative.getName(0).toString());
            String path = normalizedPath(source);
            if (!path.contains("/service/") && !path.contains("/application/")) {
                continue;
            }
            Matcher matcher = IMPORT_PATTERN.matcher(Files.readString(source));
            while (matcher.find()) {
                String importedModule = normalizeModule(matcher.group(1));
                String importedPath = matcher.group(2) == null ? "" : matcher.group(2);
                if (!module.equals(importedModule) && importedPath.matches(".*(?:mapper|persistence)\\..*Mapper")) {
                    violations.add(relative + " -> " + matcher.group());
                }
            }
        }

        printEvidence("Service 跨模块访问 Mapper", violations);
        assertThat(violations).as("Service 不得访问其他模块 Mapper").isEmpty();
    }

    /**
     * 业务目的：功能模块依赖必须可单向理解，防止互相调用形成无法拆解的循环。
     */
    @Test
    void businessModuleDependenciesAreAcyclic() throws IOException {
        Map<String, Set<String>> dependencies = moduleDependencies();
        List<String> cycles = findCycles(dependencies);

        System.out.println("架构证据：模块依赖=" + dependencies + "，循环=" + cycles);
        assertThat(cycles).as("业务模块依赖不得成环").isEmpty();
    }

    /**
     * 业务目的：公共目录只保存配置、Web、时间和 MyBatis 支持，防止业务代码重新堆入 platform/common/util。
     */
    @Test
    void platformOnlyContainsExplicitCommonCapabilities() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 2 || !relative.getName(0).toString().equals("platform")) {
                continue;
            }
            String capability = relative.getName(1).toString();
            if (!PLATFORM_PACKAGES.contains(capability)) {
                violations.add(relative + " -> 未允许公共能力 " + capability);
            }
        }

        printEvidence("公共目录越界类", violations);
        assertThat(violations).as("platform 只能保存明确公共技术能力").isEmpty();
    }

    /**
     * 业务目的：只保留模型、Agent、对象存储和 MyBatis 的真实替换边界，防止单实现转发接口回流。
     */
    @Test
    void onlyApprovedProjectInterfacesRemain() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Matcher matcher = PUBLIC_INTERFACE_PATTERN.matcher(Files.readString(source));
            while (matcher.find()) {
                String interfaceName = matcher.group(1);
                boolean moduleApi = normalizedPath(source).contains("/api/")
                        && interfaceName.endsWith("Service");
                if (!interfaceName.endsWith("Mapper")
                        && !moduleApi
                        && !ALLOWED_BOUNDARY_INTERFACES.contains(interfaceName)) {
                    violations.add(SOURCE_ROOT.relativize(source) + " -> " + interfaceName);
                }
            }
        }

        printEvidence("待删除单实现接口", violations);
        assertThat(violations).as("项目接口必须对应真实替换边界").isEmpty();
    }

    /**
     * 业务目的：跨模块代码只能引用对方模块 api 契约包，禁止引用 service/mapper/model/内部 DTO 与过程模型。
     * 存量违规以迁移清单快照登记，新增违规立即失败；阶段六按模块修复后必须同步从清单移除，不允许扩大清单合法化旧架构。
     */
    @Test
    void crossModuleCodeOnlyReferencesOtherModuleApi() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            if (relative.getNameCount() < 2 || !BUSINESS_MODULES.contains(relative.getName(0).toString())) {
                continue;
            }
            String module = normalizeModule(relative.getName(0).toString());
            Matcher matcher = IMPORT_PATTERN.matcher(Files.readString(source));
            while (matcher.find()) {
                String rawImportedModule = matcher.group(1);
                if (!BUSINESS_MODULES.contains(rawImportedModule)) {
                    continue;
                }
                String importedModule = normalizeModule(matcher.group(1));
                String importedPath = matcher.group(2) == null ? "" : matcher.group(2);
                if (!module.equals(importedModule) && !importedPath.startsWith("api.")) {
                    violations.add(relative + " -> " + matcher.group().replace(";", ""));
                }
            }
        }
        List<String> known = KNOWN_CROSS_MODULE_VIOLATIONS;
        List<String> unexpected = violations.stream().filter(item -> !known.contains(item)).sorted().toList();
        List<String> fixedButNotRemoved = known.stream().filter(item -> !violations.contains(item)).sorted().toList();

        System.out.println("架构证据：类型=跨模块非api引用，存量清单=" + known.size()
                + "，当前检测=" + violations.size()
                + "，新增违规=" + unexpected
                + "，已修复待移除=" + fixedButNotRemoved);
        assertThat(unexpected).as("跨模块新增引用必须指向对方 api 契约包").isEmpty();
        assertThat(fixedButNotRemoved).as("已迁移完成的存量违规必须同步从迁移清单移除").isEmpty();
    }

    private List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private Map<String, Set<String>> moduleDependencies() throws IOException {
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (String module : BUSINESS_MODULES) {
            dependencies.put(normalizeModule(module), new HashSet<>());
        }
        for (Path source : javaSources()) {
            Path relative = SOURCE_ROOT.relativize(source);
            String rawModule = relative.getName(0).toString();
            if (!BUSINESS_MODULES.contains(rawModule)) {
                continue;
            }
            String module = normalizeModule(rawModule);
            Matcher matcher = IMPORT_PATTERN.matcher(Files.readString(source));
            while (matcher.find()) {
                String imported = normalizeModule(matcher.group(1));
                if (dependencies.containsKey(imported) && !module.equals(imported)) {
                    dependencies.get(module).add(imported);
                }
            }
        }
        return dependencies;
    }

    private List<String> findCycles(Map<String, Set<String>> dependencies) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String module : dependencies.keySet()) {
            visit(module, dependencies, visited, visiting, path, cycles);
        }
        return cycles.stream().distinct().sorted().toList();
    }

    private void visit(
            String module,
            Map<String, Set<String>> dependencies,
            Set<String> visited,
            Set<String> visiting,
            Deque<String> path,
            List<String> cycles
    ) {
        if (visited.contains(module)) {
            return;
        }
        if (!visiting.add(module)) {
            List<String> currentPath = new ArrayList<>(path);
            int start = currentPath.indexOf(module);
            cycles.add(String.join(" -> ", currentPath.subList(start, currentPath.size())) + " -> " + module);
            return;
        }
        path.addLast(module);
        for (String dependency : dependencies.getOrDefault(module, Set.of())) {
            visit(dependency, dependencies, visited, visiting, path, cycles);
        }
        path.removeLast();
        visiting.remove(module);
        visited.add(module);
    }

    private String normalizeModule(String module) {
        return switch (module) {
            case "identity" -> "auth";
            case "knowledgegap" -> "feedback";
            default -> module;
        };
    }

    private String normalizedPath(Path source) {
        return source.toString().replace('\\', '/');
    }

    private void printEvidence(String category, List<String> violations) {
        System.out.println("架构证据：类型=" + category + "，数量=" + violations.size()
                + "，明细=" + violations);
    }
}

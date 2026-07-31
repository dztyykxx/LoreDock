package io.github.loredock.knowledge.controller;

import io.github.loredock.knowledge.converter.KnowledgeDocumentImportHttpContract;
import io.github.loredock.knowledge.converter.KnowledgeImportHttpMapper;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.KnowledgeImportCommand;
import io.github.loredock.knowledge.model.request.DocumentSourceRequest;
import io.github.loredock.knowledge.model.request.KnowledgeDocumentImportOptionsRequest;
import io.github.loredock.knowledge.model.request.KnowledgeImportOptions;
import io.github.loredock.knowledge.model.request.KnowledgeImportUpload;
import io.github.loredock.knowledge.model.response.KnowledgeImportBatchResponse;
import io.github.loredock.knowledge.service.importing.KnowledgeDocumentImportService;
import io.github.loredock.knowledge.service.project.ProjectKnowledgeScopeResolver;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 管理员同步文件导入与历史结果入口；统一 `/api/admin/**` 拦截链必须在读取文件流前完成授权。 */
@RestController
@RequestMapping(KnowledgeDocumentImportHttpContract.BASE_PATH)
public class KnowledgeDocumentImportController {

    private final KnowledgeDocumentImportService imports;
    private final ProjectKnowledgeScopeResolver scopes;

    /**
     * @param imports 同步导入用例
     * @param scopes 管理范围解析端口
     */
    public KnowledgeDocumentImportController(
            KnowledgeDocumentImportService imports,
            ProjectKnowledgeScopeResolver scopes
    ) {
        this.imports = imports;
        this.scopes = scopes;
    }

    /**
     * @param file 不可信上传文件
     * @param request JSON 选项部分
     * @return 完整导入批次结果
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeImportBatchResponse importDocuments(
            @RequestPart(KnowledgeDocumentImportHttpContract.FILE_PART) MultipartFile file,
            @Valid @RequestPart(KnowledgeDocumentImportHttpContract.OPTIONS_PART)
            KnowledgeDocumentImportOptionsRequest request
    ) throws IOException {
        KnowledgeScope scope = scopes.resolveAdmin(
                request.scope().type(), request.scope().project(), request.scope().branch());
        DocumentSourceRequest defaults = request.sourceDefaults();
        KnowledgeImportOptions options = new KnowledgeImportOptions(
                scope,
                new DocumentDirectory(request.directoryPrefix()),
                DocumentTags.of(request.tags()),
                new DocumentSource(defaults.type(), defaults.wikiUrl(), defaults.originalFilename(),
                        defaults.curationNote()));
        KnowledgeImportUpload upload = new KnowledgeImportUpload(
                file.getOriginalFilename(), file.getContentType(), file.getInputStream());
        return KnowledgeImportHttpMapper.toResponse(imports.importDocuments(new KnowledgeImportCommand(upload, options)));
    }

    /** @return 不含对象键或正文的历史批次结果。 */
    @GetMapping("/{batchId}")
    public KnowledgeImportBatchResponse getBatch(@PathVariable Long batchId) {
        return KnowledgeImportHttpMapper.toResponse(imports.getBatch(batchId));
    }
}

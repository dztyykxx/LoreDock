package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.KnowledgeDocumentImportUseCase;
import io.github.loredock.knowledge.application.KnowledgeImportCommand;
import io.github.loredock.knowledge.application.KnowledgeImportOptions;
import io.github.loredock.knowledge.application.KnowledgeImportUpload;
import io.github.loredock.knowledge.application.KnowledgeScopeResolver;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import jakarta.validation.Valid;
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

import java.io.IOException;
import java.util.UUID;

/** 管理员同步文件导入与历史结果入口；统一 `/api/admin/**` 拦截链必须在读取文件流前完成授权。 */
@RestController
@RequestMapping(KnowledgeDocumentImportHttpContract.BASE_PATH)
public class KnowledgeDocumentImportController {

    private final KnowledgeDocumentImportUseCase imports;
    private final KnowledgeScopeResolver scopes;

    /**
     * @param imports 同步导入用例
     * @param scopes 管理范围解析端口
     */
    public KnowledgeDocumentImportController(
            KnowledgeDocumentImportUseCase imports,
            KnowledgeScopeResolver scopes
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
    public KnowledgeImportBatchResponse getBatch(@PathVariable UUID batchId) {
        return KnowledgeImportHttpMapper.toResponse(imports.getBatch(batchId));
    }
}

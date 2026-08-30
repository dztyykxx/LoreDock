package io.github.loredock.memory.converter;

import io.github.loredock.memory.api.MemoryDraftInput;
import io.github.loredock.memory.api.MemoryEditInput;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryPage;
import io.github.loredock.memory.model.request.MemoryCreateRequest;
import io.github.loredock.memory.model.request.MemoryUpdateRequest;
import io.github.loredock.memory.model.response.MemoryPageResponse;
import io.github.loredock.memory.model.response.MemoryResponse;

/** 记忆 HTTP DTO 到内部契约视图的纯映射器；审计操作者由调用方（Controller）从会话填入。 */
public final class MemoryHttpMapper {

    private MemoryHttpMapper() {
    }

    /** @param full 已由服务端完成校验与审计的业务视图 */
    public static MemoryResponse toResponse(MemoryFull full) {
        return new MemoryResponse(
                full.id(),
                full.scope(),
                full.projectId(),
                full.projectIdentifier(),
                full.category(),
                full.title(),
                full.summary(),
                full.content(),
                full.status(),
                full.sourceType(),
                full.sourceRunId(),
                full.sourceConversationId(),
                full.useCount(),
                full.lastUsedAt(),
                full.createdAt(),
                full.updatedAt());
    }

    /** @param page 已过滤、分页且有界的结果 */
    public static MemoryPageResponse toPage(MemoryPage page) {
        return new MemoryPageResponse(
                page.total(), page.page(), page.size(),
                page.items().stream().map(MemoryHttpMapper::toResponse).toList());
    }

    /** @return 人工创建命令；范围与项目由客户端给出并由服务端校验 */
    public static MemoryDraftInput toDraftInput(MemoryCreateRequest request, String operatorId) {
        return new MemoryDraftInput(
                request.scope(), request.projectId(), request.category(),
                request.title(), request.summary(), request.content(), operatorId);
    }

    /** @return 人工编辑命令；scope/projectId 探针原样透传给服务端判断（非空即拒） */
    public static MemoryEditInput toEditInput(Long id, MemoryUpdateRequest request, String operatorId) {
        return new MemoryEditInput(
                id, request.category(), request.title(), request.summary(),
                request.content(), request.status(), request.scope(), request.projectId(), operatorId);
    }
}

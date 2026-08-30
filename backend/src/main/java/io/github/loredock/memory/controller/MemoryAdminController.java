package io.github.loredock.memory.controller;

import io.github.loredock.auth.api.AuthService;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.converter.MemoryHttpContract;
import io.github.loredock.memory.converter.MemoryHttpMapper;
import io.github.loredock.memory.model.request.MemoryCreateRequest;
import io.github.loredock.memory.model.request.MemoryStatusRequest;
import io.github.loredock.memory.model.request.MemoryUpdateRequest;
import io.github.loredock.memory.model.response.MemoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员记忆维护入口。角色授权由统一 `/api/admin/**` 服务端拦截链执行；
 * 人工路径不做语义判断（判断归人），但字段与 scope 校验不可绕过。
 */
@RestController
@RequestMapping(MemoryHttpContract.ADMIN_PATH)
public class MemoryAdminController {

    private final MemoryService memories;
    private final AuthService sessions;

    /** @param memories 记忆模块稳定契约 @param sessions 当前认证身份（审计操作者） */
    public MemoryAdminController(MemoryService memories, AuthService sessions) {
        this.memories = memories;
        this.sessions = sessions;
    }

    /**
     * 人工创建记忆（MANUAL 来源，不做语义判断）；PROJECT 时项目必须存在且启用。
     *
     * @param request 已完成字段级校验的创建请求
     * @return 创建后的完整视图
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryResponse create(@Valid @RequestBody MemoryCreateRequest request) {
        return MemoryHttpMapper.toResponse(memories.create(
                MemoryHttpMapper.toDraftInput(request, sessions.currentSession().username())));
    }

    /**
     * 人工编辑记忆：只允许修改分类、标题、摘要、正文与状态；
     * 请求中出现 scope/projectId 即按 {@code MEMORY_SCOPE_EDIT_FORBIDDEN} 拒绝。
     *
     * @param memoryId 记忆编号
     * @param request 编辑请求（缺省字段保持原值）
     * @return 更新后的完整视图
     */
    @PutMapping("/{memoryId}")
    public MemoryResponse update(
            @PathVariable Long memoryId,
            @Valid @RequestBody MemoryUpdateRequest request
    ) {
        return MemoryHttpMapper.toResponse(memories.update(
                MemoryHttpMapper.toEditInput(memoryId, request, sessions.currentSession().username())));
    }

    /**
     * 停用/启用记忆：停用后检索、注入与按需加载均不可见，记录保留可重新启用。
     *
     * @param memoryId 记忆编号
     * @param request 目标状态
     * @return 更新后的完整视图
     */
    @PatchMapping("/{memoryId}/status")
    public MemoryResponse changeStatus(
            @PathVariable Long memoryId,
            @Valid @RequestBody MemoryStatusRequest request
    ) {
        return MemoryHttpMapper.toResponse(
                memories.setStatus(memoryId, request.status(), sessions.currentSession().username()));
    }

    /**
     * 删除记忆：删除后的编号不可再被加载。
     *
     * @param memoryId 记忆编号
     */
    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long memoryId) {
        memories.delete(memoryId);
    }
}

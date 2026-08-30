package io.github.loredock.memory.controller;

import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryPageQuery;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.converter.MemoryHttpContract;
import io.github.loredock.memory.converter.MemoryHttpMapper;
import io.github.loredock.memory.model.response.MemoryPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆列表查询入口：登录即可读（语义全共享、无用户隔离），
 * 过滤与分页参数服务端收紧；不暴露来源 run/会话等内部检索字段。
 */
@RestController
@RequestMapping(MemoryHttpContract.LIST_PATH)
public class MemoryController {

    private final MemoryService memories;

    /** @param memories 记忆模块稳定契约 */
    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    /**
     * @param scope 范围过滤；为空不限制
     * @param category 分类过滤；为空不限制
     * @param status 状态过滤；为空不限制
     * @param keyword 标题/摘要/正文关键词过滤；为空不限制
     * @param page 页码（从 1 开始）
     * @param size 每页条数（服务端按上限收紧）
     * @return 有界分页结果（含正文与审计信息）
     */
    @GetMapping
    public MemoryPageResponse list(
            @RequestParam(required = false) MemoryScope scope,
            @RequestParam(required = false) MemoryCategory category,
            @RequestParam(required = false) MemoryStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = MemoryHttpContract.DEFAULT_PAGE + "") int page,
            @RequestParam(defaultValue = MemoryHttpContract.DEFAULT_SIZE + "") int size
    ) {
        return MemoryHttpMapper.toPage(memories.listPage(
                new MemoryPageQuery(scope, category, status, keyword, page, size)));
    }
}

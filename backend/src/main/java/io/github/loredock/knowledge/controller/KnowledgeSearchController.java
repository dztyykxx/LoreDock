package io.github.loredock.knowledge.controller;

import io.github.loredock.knowledge.converter.KnowledgeSearchHttpContract;
import io.github.loredock.knowledge.converter.KnowledgeSearchHttpMapper;
import io.github.loredock.knowledge.model.request.KnowledgeSearchFilters;
import io.github.loredock.knowledge.model.request.KnowledgeSearchQuery;
import io.github.loredock.knowledge.model.request.KnowledgeSearchRequest;
import io.github.loredock.knowledge.model.response.KnowledgeSearchHttpResponse;
import io.github.loredock.knowledge.service.search.KnowledgeSearchServiceImpl;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录 ADMIN 与 MEMBER 共用的知识搜索入口，只负责 HTTP 绑定和安全响应映射。 */
@RestController
@RequestMapping(KnowledgeSearchHttpContract.BASE_PATH)
public class KnowledgeSearchController {

    private final KnowledgeSearchServiceImpl searches;

    /** @param searches 统一知识搜索应用用例 */
    public KnowledgeSearchController(KnowledgeSearchServiceImpl searches) {
        this.searches = searches;
    }

    /**
     * GET 调用幂等，不接受 generation、向量、SQL、候选数或融合权重等内部控制参数。
     *
     * @param request 公开查询参数
     * @return 固定单一 generation 的有限可引用结果
     */
    @GetMapping
    public KnowledgeSearchHttpResponse search(@Valid @ModelAttribute KnowledgeSearchRequest request) {
        return KnowledgeSearchHttpMapper.toResponse(searches.search(new KnowledgeSearchQuery(
                request.context(), request.project(), request.branch(), request.query(), request.mode(),
                new KnowledgeSearchFilters(request.tag(), request.format(), request.sourceType()), request.limit())));
    }
}

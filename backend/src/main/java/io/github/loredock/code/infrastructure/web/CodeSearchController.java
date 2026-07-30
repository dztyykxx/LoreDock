package io.github.loredock.code.infrastructure.web;

import io.github.loredock.code.application.CodeSearchQuery;
import io.github.loredock.code.application.CodeSearchResponse;
import io.github.loredock.code.application.CodeSearchTarget;
import io.github.loredock.code.application.CodeSearchUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 与 MEMBER 共用的活动代码关键词搜索入口。 */
@RestController
public class CodeSearchController {

    private final CodeSearchUseCase searches;

    /** @param searches 固定活动范围且服务端构造 Lucene 查询的用例 */
    public CodeSearchController(CodeSearchUseCase searches) {
        this.searches = searches;
    }

    /** @return 有限纯文本命中；空结果不扩大项目、分支或历史范围。 */
    @GetMapping("/api/projects/{identifier}/code-search")
    public CodeSearchResponse search(
            @PathVariable String identifier,
            @RequestParam(required = false) String branch,
            @RequestParam String query,
            @RequestParam(required = false) CodeSearchTarget target,
            @RequestParam(required = false) String pathPrefix,
            @RequestParam(required = false) Integer limit
    ) {
        return searches.search(new CodeSearchQuery(identifier, branch, query, target, pathPrefix, limit));
    }
}

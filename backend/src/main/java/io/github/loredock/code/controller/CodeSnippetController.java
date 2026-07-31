package io.github.loredock.code.controller;

import io.github.loredock.code.model.request.CodeSnippetQuery;
import io.github.loredock.code.model.response.CodeSnippetResponse;
import io.github.loredock.code.service.CodeQueryServiceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 与 MEMBER 共用的活动代码 StoredField 片段入口。 */
@RestController
public class CodeSnippetController {

    private final CodeQueryServiceImpl snippets;

    /** @param snippets 固定活动范围和有限行读取用例 */
    public CodeSnippetController(CodeQueryServiceImpl snippets) {
        this.snippets = snippets;
    }

    /** @return 精确逻辑路径的有界纯文本片段。 */
    @GetMapping("/api/projects/{identifier}/code-snippets")
    public CodeSnippetResponse read(
            @PathVariable String identifier,
            @RequestParam(required = false) String branch,
            @RequestParam String path,
            @RequestParam(required = false) Integer startLine,
            @RequestParam(required = false) Integer lineCount
    ) {
        return snippets.read(new CodeSnippetQuery(identifier, branch, path, startLine, lineCount));
    }
}

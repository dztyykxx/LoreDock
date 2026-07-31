package io.github.loredock.code.model.response;

import io.github.loredock.code.model.result.CodeSearchResult;
import java.util.List;

/** 有界代码搜索结果；无命中时返回空列表，不扩大项目、分支或历史范围。 */
public record CodeSearchResponse(List<CodeSearchResult> items) {
}

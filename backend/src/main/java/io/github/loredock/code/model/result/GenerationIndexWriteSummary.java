package io.github.loredock.code.model.result;

import java.util.List;

/** generation 流式写入后的轻量校验摘要，不保留文件正文。 */
public record GenerationIndexWriteSummary(List<String> paths) {

    /** 冻结规范路径列表，供关闭后重开验证文档数和路径集合。 */
    public GenerationIndexWriteSummary {
        paths = List.copyOf(paths);
    }

    /** @return 已写入文档数量 */
    public int documentCount() {
        return paths.size();
    }
}

package io.github.loredock.code.exception;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** 数据库已解析活动 generation，但 Lucene 目录暂时无法打开或读取。 */
public class CodeIndexUnavailableException extends ApplicationException {
    /** 创建不回退旧 commit、其他分支或候选目录的 503。 */
    public CodeIndexUnavailableException(Throwable cause) {
        super(ErrorCode.CODE_INDEX_UNAVAILABLE, "active code index unavailable", cause);
    }
}

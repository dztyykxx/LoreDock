package io.github.loredock.job.application;

import io.github.loredock.job.domain.JobFailure;
import io.github.loredock.job.domain.InvalidJobTransitionException;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import org.springframework.stereotype.Component;

/**
 * 把处理器异常分类为稳定错误码，并在写入任务表前统一脱敏和限长。
 */
@Component
public class JobFailureClassifier {

    private static final int MAX_MESSAGE_LENGTH = 1024;
    private final SensitiveDataRedactor redactor;

    /**
     * @param redactor 平台敏感信息脱敏器
     */
    public JobFailureClassifier(SensitiveDataRedactor redactor) {
        this.redactor = redactor;
    }

    /**
     * @param failure 原始工作异常
     * @return 可安全持久化的失败信息
     */
    public JobFailure classify(Throwable failure) {
        String code;
        if (failure instanceof InvalidJobTransitionException) {
            code = "INVALID_JOB_TRANSITION";
        } else if (failure instanceof ApplicationException applicationException) {
            code = applicationException.errorCode().name();
        } else {
            code = "UNEXPECTED_ERROR";
        }
        String redacted = redactor.redact(failure.getMessage());
        return new JobFailure(code, redacted.substring(0, Math.min(redacted.length(), MAX_MESSAGE_LENGTH)));
    }
}

package io.github.loredock.platform.web.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供前端启动诊断所需的最小公开状态，只暴露固定服务标识和运行状态。
 */
@RestController
@RequestMapping("/api/v1/system/status")
public class SystemStatusController {

    /**
     * 返回当前后端进程的公开状态，不读取或返回任何运行配置。
     *
     * @return LoreDock 后端可用状态
     */
    @GetMapping
    public SystemStatusResponse status() {
        return new SystemStatusResponse("loredock", "UP");
    }
}

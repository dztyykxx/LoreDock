package io.github.loredock.project.model.request;

import io.github.loredock.project.model.enums.ProjectStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 幂等项目状态更新请求。
 *
 * @param status 目标状态，只接受 ENABLED 或 DISABLED
 */
public record ChangeProjectStatusRequest(@NotNull ProjectStatus status) {
}

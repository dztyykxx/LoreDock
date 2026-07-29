package io.github.loredock.project.application;

import io.github.loredock.project.domain.ProjectStatus;

/**
 * 设置项目目标状态的幂等输入。
 *
 * @param status 目标状态
 */
public record ChangeProjectStatusCommand(ProjectStatus status) {
}

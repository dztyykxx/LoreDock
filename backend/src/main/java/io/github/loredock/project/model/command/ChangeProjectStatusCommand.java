package io.github.loredock.project.model.command;

import io.github.loredock.project.model.enums.ProjectStatus;

/**
 * 设置项目目标状态的幂等输入。
 *
 * @param status 目标状态
 */
public record ChangeProjectStatusCommand(ProjectStatus status) {
}

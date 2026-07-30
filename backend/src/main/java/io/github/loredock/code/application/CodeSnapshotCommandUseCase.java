package io.github.loredock.code.application;

import java.util.UUID;

/** 管理员代码快照上传与重建端口；入口必须先完成 ADMIN 授权，成员请求不得读取上传正文。 */
public interface CodeSnapshotCommandUseCase {

    /**
     * 非幂等提交新快照。成功只表示对象、CANDIDATE 快照和 PENDING 任务已受理，不表示可查询。
     * 同一分支已有构建或重建任务时失败；项目停用、分支不归属、commit/ZIP 外层类型或大小非法时不登记任务。
     *
     * @param command 上传范围、声明 commit 与不可信 ZIP 流
     * @return 可轮询的任务状态
     */
    CodeSnapshotJobView upload(UploadCodeSnapshotCommand command);

    /**
     * 非幂等重建当前活动快照。只复用该快照原始对象，失败时必须保留原活动 generation。
     *
     * @param snapshotId 当前活动快照 UUID
     * @return 新建重建任务状态
     */
    CodeSnapshotJobView reindex(UUID snapshotId);
}

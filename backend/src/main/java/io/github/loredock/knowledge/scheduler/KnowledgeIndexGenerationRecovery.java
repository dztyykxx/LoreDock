package io.github.loredock.knowledge.scheduler;

import io.github.loredock.knowledge.mapper.KnowledgeIndexGenerationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 清理上次进程中断或取消任务遗留的不可见 BUILDING generation。
 *
 * <p>通用任务恢复先把陈旧 RUNNING 任务标为 FAILED，本协调器随后依赖任务终态删除关联 generation；
 * 删除通过外键级联投影、检索元数据与分块，不会触碰 ACTIVE/RETIRED。</p>
 */
@Component
public class KnowledgeIndexGenerationRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeIndexGenerationRecovery.class);

    private final KnowledgeIndexGenerationMapper generations;

    /** @param generations 知识投影 generation Mapper */
    public KnowledgeIndexGenerationRecovery(KnowledgeIndexGenerationMapper generations) {
        this.generations = generations;
    }

    /**
     * @return 本次清理的失败或取消任务 BUILDING generation 数量
     */
    @Transactional
    public int recoverAbandonedBuildingGenerations() {
        int recovered = generations.deleteAbandonedBuildingGenerations();
        LOGGER.info("knowledge_index_generation_recovery completed recoveredGenerationCount={}", recovered);
        return recovered;
    }

    /** 通用任务恢复完成后清理级联数据，避免仍处于 RUNNING 的有效构建被误删。 */
    @Transactional
    @EventListener(ApplicationReadyEvent.class)
    @Order(110)
    public void onApplicationReady() {
        recoverAbandonedBuildingGenerations();
    }
}

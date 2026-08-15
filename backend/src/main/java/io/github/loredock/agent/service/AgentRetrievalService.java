package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.AgentRunRetrievalMapper;
import io.github.loredock.agent.model.entity.AgentRunRetrievalEntity;
import io.github.loredock.agent.model.result.AgentRunRetrieval;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识检索评估记录服务：持久化每次 knowledge_search 实际提供给模型的内容，供同进程评估运行器读取。 */
@Service
@Slf4j
public class AgentRetrievalService {

    private final AgentRunRetrievalMapper retrievals;
    private final Clock timeProvider;
    private final ObjectMapper objectMapper;

    /**
     * @param retrievals 检索记录 Mapper
     * @param timeProvider UTC 时间源
     * @param objectMapper 检索文档片段 JSON 编解码器
     */
    public AgentRetrievalService(
            AgentRunRetrievalMapper retrievals,
            Clock timeProvider,
            ObjectMapper objectMapper
    ) {
        this.retrievals = retrievals;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 按调用顺序追加一次检索记录，返回本次序号。运行内检索串行执行，序号由已存记录数推得。
     *
     * @param runId 当前运行标识
     * @param query 模型本轮发起的检索查询
     * @param documents 按检索顺序的全部候选文档
     * @return 本次检索序号
     */
    @Transactional
    public int append(Long runId, String query, List<AgentRunRetrieval.RetrievedDocument> documents) {
        int sequenceNo = retrievals.selectCount(new LambdaQueryWrapper<AgentRunRetrievalEntity>()
                .eq(AgentRunRetrievalEntity::getRunId, runId)).intValue() + 1;
        AgentRunRetrievalEntity entity = AgentRunRetrievalEntity.builder()
                .runId(runId).sequenceNo(sequenceNo).queryText(query)
                .documents(json(documents)).createdAt(timeProvider.instant()).build();
        retrievals.insert(entity);
        log.info("agent_run_retrieval persisted runId={} sequence={} documentCount={}",
                runId, sequenceNo, documents.size());
        return sequenceNo;
    }

    /**
     * 按调用顺序读取某运行的全部检索记录，供评估运行器还原模型本轮实际看到的内容。
     *
     * @param runId 运行标识
     * @return 按 sequenceNo 升序的检索记录
     */
    @Transactional(readOnly = true)
    public List<AgentRunRetrieval> findByRunId(Long runId) {
        return retrievals.selectList(new LambdaQueryWrapper<AgentRunRetrievalEntity>()
                        .eq(AgentRunRetrievalEntity::getRunId, runId)
                        .orderByAsc(AgentRunRetrievalEntity::getSequenceNo))
                .stream().map(this::domain).toList();
    }

    private AgentRunRetrieval domain(AgentRunRetrievalEntity entity) {
        return new AgentRunRetrieval(
                entity.getRunId(), entity.getSequenceNo(), entity.getQueryText(),
                documents(entity.getDocuments()));
    }

    private String json(List<AgentRunRetrieval.RetrievedDocument> documents) {
        try {
            return objectMapper.writeValueAsString(documents);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent run retrieval documents serialization failed", exception);
        }
    }

    private List<AgentRunRetrieval.RetrievedDocument> documents(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentRunRetrieval.RetrievedDocument>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("agent run retrieval documents deserialization failed", exception);
        }
    }
}

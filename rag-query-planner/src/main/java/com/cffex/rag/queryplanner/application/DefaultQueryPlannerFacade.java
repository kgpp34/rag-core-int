package com.cffex.rag.queryplanner.application;

import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.PlanType;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;
import com.cffex.rag.common.service.QueryPlannerFacade;
import com.cffex.rag.queryplanner.config.QueryPlannerProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Query planner 默认门面实现。
 *
 * <p>职责是根据计划类型选择具体策略，并在入口处统一记录规划链路日志。
 */
@Service
public class DefaultQueryPlannerFacade implements QueryPlannerFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryPlannerFacade.class);

    private final QueryPlannerProperties properties;
    private final Map<PlanType, PlannerStrategy> plannersByType;

    public DefaultQueryPlannerFacade(
            List<PlannerStrategy> plannerStrategies,
            QueryPlannerProperties properties
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.plannersByType = List.copyOf(Objects.requireNonNull(plannerStrategies)).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        PlannerStrategy::planType,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("duplicate planner strategy for plan type: " + left.planType());
                        }
                ));
    }

    @Override
    public ExecutionPlan plan(QueryPlanRequest request) {
        PlanType planType = request.planType() == null ? properties.getDefaultPlanType() : request.planType();
        log.info("开始生成查询计划，计划类型={}，问题长度={}，文档数={}",
                planType,
                request.query().length(),
                request.docIds().size());
        ExecutionPlan executionPlan = requirePlanner(planType).plan(request);
        log.info("查询计划生成完成，计划类型={}，检索子计划数={}",
                executionPlan.planType(),
                executionPlan.retrievalPlans().size());
        return executionPlan;
    }

    @Override
    public ExecutionPlan plan(RetrievalContext context) {
        log.info("开始根据检索上下文生成查询计划，问题长度={}，知识库数={}，文档数={}",
                context.query().length(),
                context.targetKnowledgeBaseIds().size(),
                context.targetDocIds().size());
        ExecutionPlan executionPlan = requirePlanner(PlanType.STANDARD_RETRIEVAL).plan(context);
        log.info("基于检索上下文的查询计划生成完成，检索子计划数={}",
                executionPlan.retrievalPlans().size());
        return executionPlan;
    }

    private PlannerStrategy requirePlanner(PlanType planType) {
        PlannerStrategy planner = plannersByType.get(planType);
        if (planner == null) {
            throw new UnsupportedOperationException("plan type is not supported yet: " + planType);
        }
        return planner;
    }
}

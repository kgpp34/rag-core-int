package com.cffex.rag.common.service;

import com.cffex.rag.common.domain.query.ExecutionPlan;
import com.cffex.rag.common.domain.query.QueryPlanRequest;
import com.cffex.rag.common.domain.retrieval.RetrievalContext;

/**
 * Query planner 出站端口：将用户意图（及可选的直连请求）转化为 {@link ExecutionPlan}。
 *
 * <p>实现负责：元数据解析、知识库路由、模型选择、召回规格组装、排序策略决定。
 * 高层计划中的 {@code RetrievalPlan} 是 retrieval-engine 的直接输入，引擎不再进行任何元数据查询。
 */
public interface QueryPlannerFacade {

    /** 从业务域路由请求生成执行计划（含知识库解析、模型决策）。 */
    ExecutionPlan plan(QueryPlanRequest request);

    /**
     * 从已有检索上下文直接生成执行计划。
     *
     * <p>适用于调用方已掌握知识库 ID 和模型 ID、只需 planner 完成元数据解析和参数组装的场景。
     */
    ExecutionPlan plan(RetrievalContext context);
}

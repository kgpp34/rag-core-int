CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
    );

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE TABLE IF NOT EXISTS t_rag_conversation_summary (
    conversation_id VARCHAR(255) NOT NULL PRIMARY KEY,
    summary_content TEXT NOT NULL,
    summarized_message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_rag_trace_event (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128),
    source VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    event_name VARCHAR(128) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_trace_time
ON t_rag_trace_event(trace_id, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_request_time
ON t_rag_trace_event(request_id, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_stage_time
ON t_rag_trace_event(stage, event_time);

CREATE INDEX IF NOT EXISTS idx_rag_trace_event_name_time
ON t_rag_trace_event(event_name, event_time);

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
    );

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE TABLE IF NOT EXISTS t_rag_agentic_run (
    run_id UUID PRIMARY KEY,
    request_id VARCHAR(128),
    conversation_id VARCHAR(255),
    user_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    query TEXT NOT NULL,
    output JSONB,
    error JSONB,
    memory_written BOOLEAN NOT NULL DEFAULT FALSE,
    memory_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    memory_attempts INTEGER NOT NULL DEFAULT 0,
    memory_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

ALTER TABLE t_rag_agentic_run
ADD COLUMN IF NOT EXISTS memory_status VARCHAR(32) NOT NULL DEFAULT 'pending';

ALTER TABLE t_rag_agentic_run
ADD COLUMN IF NOT EXISTS memory_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE t_rag_agentic_run
ADD COLUMN IF NOT EXISTS memory_error TEXT;

UPDATE t_rag_agentic_run
SET memory_status = CASE
    WHEN memory_written THEN 'written'
    WHEN conversation_id IS NULL THEN 'not_required'
    ELSE memory_status
END;

CREATE TABLE IF NOT EXISTS t_rag_memory_exchange (
    exchange_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_memory_exchange_conversation
ON t_rag_memory_exchange(conversation_id, created_at);

CREATE INDEX IF NOT EXISTS idx_rag_agentic_run_conversation_created
ON t_rag_agentic_run(conversation_id, created_at);

CREATE INDEX IF NOT EXISTS idx_rag_agentic_run_request
ON t_rag_agentic_run(request_id);

CREATE INDEX IF NOT EXISTS idx_rag_agentic_run_status_updated
ON t_rag_agentic_run(status, updated_at);

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

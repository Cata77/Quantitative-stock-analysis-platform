-- A backlog must not be sorted for every single delivery claim. Keep the existing
-- retry-date index and add FIFO ordering plus an index for exhausted attempts.
CREATE INDEX idx_outbox_pending_fifo ON operations.outbox_events (created_at, event_id)
    WHERE status IN ('PENDING','CLAIMED','FAILED_RETRYABLE');
CREATE INDEX idx_outbox_pending_attempts ON operations.outbox_events (attempt_count)
    WHERE status IN ('PENDING','CLAIMED','FAILED_RETRYABLE');

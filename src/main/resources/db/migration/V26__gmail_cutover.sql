-- Owner inserts the singleton only after reconciliation. No rehearsal backfill.
CREATE TABLE gmail_cutover (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    enabled boolean NOT NULL DEFAULT false,
    since_epoch bigint NOT NULL CHECK (since_epoch > 0),
    window_start bigint NOT NULL CHECK (window_start >= since_epoch),
    window_end bigint CHECK (window_end > window_start),
    scan_start bigint CHECK (scan_start BETWEEN since_epoch AND window_start),
    CHECK ((scan_start IS NULL) = (window_end IS NULL)),
    page_token text CHECK (window_end IS NOT NULL OR page_token IS NULL),
    generation bigint NOT NULL DEFAULT 0
);
CREATE TABLE gmail_mailbox_actions (
    message_id varchar(128) PRIMARY KEY REFERENCES gmail_alert_messages(message_id),
    queued_at timestamptz NOT NULL DEFAULT now(),
    next_attempt timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0,
    applied_at timestamptz
);
CREATE INDEX gmail_mailbox_pending ON gmail_mailbox_actions(next_attempt) WHERE applied_at IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='job_agent_app') THEN
        REVOKE ALL ON gmail_cutover FROM job_agent_app;
        GRANT SELECT, UPDATE(window_start,window_end,scan_start,page_token,generation) ON gmail_cutover TO job_agent_app;
    END IF;
END $$;

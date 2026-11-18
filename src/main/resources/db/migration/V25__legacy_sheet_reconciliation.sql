-- Immutable full snapshots. The predecessor chain has one root and cannot fork.
CREATE TABLE sheet_snapshots (
    digest text PRIMARY KEY,
    previous text UNIQUE REFERENCES sheet_snapshots(digest),
    captured_at timestamptz NOT NULL,
    document jsonb NOT NULL
);
CREATE UNIQUE INDEX sheet_single_origin ON sheet_snapshots ((true)) WHERE previous IS NULL;

CREATE TABLE sheet_reconciliation (
    snapshot text NOT NULL REFERENCES sheet_snapshots(digest),
    tab text NOT NULL,
    position integer NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('IMPORTED','LINKED','UNCHANGED','REVIEW')),
    reasons text[] NOT NULL,
    vacancy_id bigint REFERENCES vacancies(id),
    added boolean NOT NULL,
    edited boolean NOT NULL,
    moved boolean NOT NULL,
    PRIMARY KEY (snapshot, tab, position)
);
CREATE TABLE sheet_removals (
    snapshot text NOT NULL REFERENCES sheet_snapshots(digest),
    tab text NOT NULL,
    previous_position integer NOT NULL,
    PRIMARY KEY (snapshot, tab, previous_position)
);

-- The cloud migrator normally grants application DML on new public tables.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'job_agent_app') THEN
        REVOKE ALL ON sheet_snapshots, sheet_reconciliation, sheet_removals FROM job_agent_app;
    END IF;
END $$;

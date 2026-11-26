-- Java owns revision calculation. No SQL reconstruction or historical-evaluation rewrite.
CREATE TABLE vacancy_evaluation_inputs (
    vacancy_id bigint PRIMARY KEY REFERENCES vacancies(id) ON DELETE CASCADE,
    source_item_id bigint NOT NULL REFERENCES source_items(id),
    input_fingerprint varchar(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    original_url text NOT NULL,
    application_url text,
    expires_at timestamptz,
    refreshed_at timestamptz NOT NULL DEFAULT now()
);

-- A missing projection is intentionally unknown, never a historical/current fallback.
CREATE FUNCTION invalidate_vacancy_evaluation_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM vacancy_evaluation_inputs WHERE vacancy_id = NEW.id;
    RETURN NEW;
END $$;
CREATE TRIGGER vacancy_evaluation_input_changed
AFTER UPDATE OF title, location, description, content_digest ON vacancies
FOR EACH ROW WHEN (ROW(OLD.title, OLD.location, OLD.description, OLD.content_digest)
    IS DISTINCT FROM ROW(NEW.title, NEW.location, NEW.description, NEW.content_digest))
EXECUTE FUNCTION invalidate_vacancy_evaluation_input();

CREATE FUNCTION invalidate_source_evaluation_input() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE affected bigint;
BEGIN
    FOR affected IN SELECT vacancy_id FROM vacancy_sources
        WHERE source_item_id = NEW.id ORDER BY vacancy_id LOOP
        PERFORM id FROM vacancies WHERE id = affected FOR UPDATE;
        DELETE FROM vacancy_evaluation_inputs WHERE vacancy_id = affected;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER source_evaluation_input_changed
AFTER UPDATE OF original_url, raw_payload, content_digest ON source_items
FOR EACH ROW WHEN (ROW(OLD.original_url, OLD.raw_payload, OLD.content_digest)
    IS DISTINCT FROM ROW(NEW.original_url, NEW.raw_payload, NEW.content_digest))
EXECUTE FUNCTION invalidate_source_evaluation_input();

CREATE FUNCTION invalidate_linked_evaluation_input() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM id FROM vacancies WHERE id = OLD.vacancy_id FOR UPDATE;
        DELETE FROM vacancy_evaluation_inputs WHERE vacancy_id = OLD.vacancy_id;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        PERFORM id FROM vacancies WHERE id = NEW.vacancy_id FOR UPDATE;
        DELETE FROM vacancy_evaluation_inputs WHERE vacancy_id = NEW.vacancy_id;
        RETURN NEW;
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER linked_evaluation_input_changed AFTER INSERT OR UPDATE OR DELETE ON vacancy_sources
FOR EACH ROW EXECUTE FUNCTION invalidate_linked_evaluation_input();

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'job_agent_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON vacancy_evaluation_inputs TO job_agent_app;
    END IF;
END $$;

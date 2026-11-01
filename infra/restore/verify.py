"""Read-only, fail-closed checks of one isolated restored Aurora cluster."""

import os
import sys

import psycopg

EXPECTED_TABLES = (
    "agent_runs", "source_items", "employers", "vacancies", "vacancy_sources",
    "outbox_events", "flyway_schema_history",
)
CA_FILE = "/opt/restore/certs/us-east-1-bundle.pem"


def verify(connection, expected_runs):
    # A read-only transaction prevents the verifier from altering restored data.
    with connection.transaction():
        with connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute("SELECT current_user, current_database()")
            if cursor.fetchone() != ("job_agent_app", "job_agent"):
                raise ValueError("unexpected verifier identity")
            cursor.execute("SELECT version::integer FROM public.flyway_schema_history "
                           "WHERE success = true AND type = 'SQL' ORDER BY installed_rank")
            if [row[0] for row in cursor.fetchall()] != list(range(1, 14)):
                raise ValueError("migration history is incomplete or unexpected")
            cursor.execute("SELECT to_regclass(%s)", ("public.flyway_schema_history",))
            if cursor.fetchone()[0] is None:
                raise ValueError("migration history missing")
            for table in EXPECTED_TABLES:
                cursor.execute("SELECT to_regclass(%s)", (f"public.{table}",))
                if cursor.fetchone()[0] is None:
                    raise ValueError("expected schema table missing")
            cursor.execute("SELECT t.relname, c.conname, c.contype "
                           "FROM pg_constraint c "
                           "JOIN pg_class t ON t.oid = c.conrelid "
                           "JOIN pg_namespace n ON n.oid = t.relnamespace "
                           "WHERE n.nspname = 'public' AND c.conname = ANY(%s)",
                           (["source_items_identity", "vacancies_identity",
                             "outbox_state_check", "agent_runs_result_check"],))
            if set(cursor.fetchall()) != {
                    ("source_items", "source_items_identity", "u"),
                    ("vacancies", "vacancies_identity", "u"),
                    ("outbox_events", "outbox_state_check", "c"),
                    ("agent_runs", "agent_runs_result_check", "c")}:
                raise ValueError("representative constraints missing")
            cursor.execute("SELECT has_schema_privilege(current_user, 'public', 'CREATE'), "
                           "has_table_privilege(current_user, 'public.flyway_schema_history', 'INSERT'), "
                           "has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT')")
            create_schema, write_history, read_history = cursor.fetchone()
            if create_schema or write_history or not read_history:
                raise ValueError("application privilege boundary differs")
            cursor.execute("SELECT COUNT(*) FROM public.agent_runs")
            if cursor.fetchone()[0] != expected_runs:
                raise ValueError("agent_runs count differs from source reference")
    print("Restore verification passed: V1-V13, schema, app permissions and reference count")


def main():
    try:
        expected = int(os.environ["EXPECTED_AGENT_RUNS"])
        if expected < 0:
            raise ValueError("negative reference count")
        with psycopg.connect(host=os.environ["DB_HOST"], dbname=os.environ["DB_NAME"],
                             user=os.environ["DB_USER"], password=os.environ["DB_PASSWORD"],
                             sslmode="verify-full", sslrootcert=CA_FILE, connect_timeout=45,
                             options="-c statement_timeout=60000", autocommit=True) as connection:
            verify(connection, expected)
        return 0
    except (KeyError, ValueError, OSError, psycopg.Error):
        print("Restore verification failed; inspect configuration and restored state without exposing credentials", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

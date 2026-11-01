"""Offline checks of fail-closed restore verification decisions."""

import sys
import unittest
from contextlib import nullcontext
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify import verify  # noqa: E402


class FakeCursor:
    def __init__(self, versions=None, can_create=False, can_write_history=False,
                 can_read_history=True, run_count=0, constraints=None):
        self.versions = list(range(1, 14)) if versions is None else versions
        self.permissions = (can_create, can_write_history, can_read_history)
        self.run_count = run_count
        self.constraints = constraints
        self.query = ""
        self.parameters = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, query, parameters=None):
        self.query = query
        self.parameters = parameters

    def fetchone(self):
        if "current_user" in self.query and "current_database" in self.query:
            return ("job_agent_app", "job_agent")
        if "to_regclass" in self.query:
            return (self.parameters[0],)
        if "has_schema_privilege" in self.query:
            return self.permissions
        if "COUNT(*)" in self.query:
            return (self.run_count,)
        raise AssertionError(self.query)

    def fetchall(self):
        if "pg_constraint" in self.query:
            return self.constraints if self.constraints is not None else [
                ("source_items", "source_items_identity", "u"),
                ("vacancies", "vacancies_identity", "u"),
                ("outbox_events", "outbox_state_check", "c"),
                ("agent_runs", "agent_runs_result_check", "c")]
        if "flyway_schema_history" in self.query:
            return [(version,) for version in self.versions]
        raise AssertionError(self.query)


class FakeConnection:
    def __init__(self, cursor):
        self.test_cursor = cursor

    def transaction(self):
        return nullcontext()

    def cursor(self):
        return self.test_cursor


class RestoreVerifierTest(unittest.TestCase):
    def test_expected_history_and_restricted_role_pass(self):
        verify(FakeConnection(FakeCursor()), 0)

    def test_missing_migration_fails(self):
        with self.assertRaisesRegex(ValueError, "migration history"):
            verify(FakeConnection(FakeCursor(versions=list(range(1, 13)))), 0)

    def test_schema_create_grant_fails(self):
        with self.assertRaisesRegex(ValueError, "privilege boundary"):
            verify(FakeConnection(FakeCursor(can_create=True)), 0)

    def test_reference_mismatch_fails(self):
        with self.assertRaisesRegex(ValueError, "reference"):
            verify(FakeConnection(FakeCursor(run_count=1)), 0)

    def test_constraint_on_wrong_table_or_with_wrong_type_fails(self):
        constraints = [
            ("other_table", "source_items_identity", "u"),
            ("vacancies", "vacancies_identity", "c"),
            ("outbox_events", "outbox_state_check", "c"),
            ("agent_runs", "agent_runs_result_check", "c")]
        with self.assertRaisesRegex(ValueError, "representative constraints"):
            verify(FakeConnection(FakeCursor(constraints=constraints)), 0)


if __name__ == "__main__":
    unittest.main()

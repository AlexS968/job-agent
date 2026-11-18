"""Exercise the page projection and reader grants against project migrations."""

import importlib.util
import json
import uuid
from pathlib import Path
import re
import subprocess
import sys
import time
import types
import unittest

sys.modules.setdefault("boto3", types.SimpleNamespace(client=lambda _: None))
sys.modules.setdefault("botocore", types.ModuleType("botocore"))
sys.modules.setdefault("botocore.exceptions", types.SimpleNamespace(ClientError=Exception))
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("page_sql", Path(__file__).parent / "lambda" / "handler.py")
page = importlib.util.module_from_spec(spec)
spec.loader.exec_module(page)
spec = importlib.util.spec_from_file_location("reader_grants", Path(__file__).with_name("prepare_reader.py"))
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
sys.modules["prepare_reader"] = reader
spec = importlib.util.spec_from_file_location("feedback_grants", Path(__file__).with_name("prepare_feedback.py"))
feedback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feedback)


def safe_error(stderr):
    excerpt = re.sub(r"[\x00-\x1f\x7f]", " ", stderr or "")[:800]
    return re.sub(r"(?i)(password|secret|token)\s*[:=]\s*\S+", r"\1=<redacted>", excerpt)


def startup_connection_failure(stderr):
    message = (stderr or "").lower()
    return ("connection to server" in message and
            ("failed" in message or "starting up" in message)) or \
        "server closed the connection unexpectedly" in message


def create_database(container, run=subprocess.run, pause=time.sleep, now=time.monotonic):
    deadline = now() + 10
    command = ["docker", "exec", container, "createdb", "-U", "postgres", "job_agent"]
    last_detail = "no response"
    while True:
        remaining = deadline - now()
        if remaining <= 0:
            raise AssertionError(f"Isolated PostgreSQL database creation timed out: {last_detail}")
        try:
            result = run(command, capture_output=True, text=True, timeout=min(2, remaining))
        except subprocess.TimeoutExpired as error:
            stderr = error.stderr.decode(errors="replace") if isinstance(error.stderr, bytes) else error.stderr
            raise AssertionError(f"Isolated PostgreSQL createdb command timed out: {safe_error(stderr)}") from error
        if result.returncode == 0:
            return
        detail = safe_error(result.stderr)
        last_detail = detail
        if not startup_connection_failure(result.stderr) or now() >= deadline:
            raise AssertionError(f"Isolated PostgreSQL database creation failed: {detail}")
        pause(max(0, min(0.25, deadline - now())))


class DatabaseStartupTest(unittest.TestCase):
    def test_transient_restart_error_is_retried(self):
        replies = iter((subprocess.CompletedProcess([], 1, "", "connection to server failed: starting up"),
                        subprocess.CompletedProcess([], 0, "", "")))
        calls = []

        def run(command, **kwargs):
            calls.append((command, kwargs))
            return next(replies)

        create_database("synthetic-container", run=run, pause=lambda _: None)
        self.assertEqual(2, len(calls))
        self.assertEqual("createdb", calls[0][0][3])

    def test_unrelated_error_fails_immediately_with_redacted_stderr(self):
        calls = []

        def run(command, **_kwargs):
            calls.append(command)
            return subprocess.CompletedProcess([], 1, "", "database already exists password=synthetic-secret")

        with self.assertRaisesRegex(AssertionError, "database already exists password=<redacted>"):
            create_database("synthetic-container", run=run)
        self.assertEqual(1, len(calls))

    def test_persistent_connection_error_stops_at_deadline_with_stderr(self):
        elapsed = [0.0]
        calls = []

        def run(command, **_kwargs):
            calls.append(command)
            return subprocess.CompletedProcess([], 1, "", "connection to server failed: starting up")

        def pause(seconds):
            elapsed[0] += seconds

        with self.assertRaisesRegex(AssertionError, "timed out: connection to server failed: starting up"):
            create_database("synthetic-container", run=run, pause=pause, now=lambda: elapsed[0])
        self.assertLessEqual(len(calls), 41)


class SqlIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = f"job-agent-page-it-{__import__('os').getpid()}"
        subprocess.run(["docker", "run", "--rm", "-d", "--name", cls.container,
                        "-e", "POSTGRES_PASSWORD=synthetic-test-only", "postgres:17.11-alpine"],
                       check=True, capture_output=True)
        try:
            for _ in range(60):
                ready = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-U", "postgres"],
                                       capture_output=True)
                if ready.returncode == 0:
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError("Isolated PostgreSQL did not become ready")
            create_database(cls.container)
            for migration in sorted((ROOT / "src/main/resources/db/migration").glob("V*.sql"),
                                    key=lambda p: int(p.name.split("__")[0][1:])):
                cls.sql(migration.read_text())
        except Exception:
            subprocess.run(["docker", "stop", cls.container], capture_output=True)
            raise

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker", "stop", cls.container], capture_output=True)

    @classmethod
    def sql(cls, statement):
        result = subprocess.run(["docker", "exec", "-i", cls.container, "psql", "-X", "-q", "-A", "-t",
                                 "-F", "|", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "job_agent"],
                                input=statement, text=True, capture_output=True)
        if result.returncode:
            raise AssertionError(result.stderr[:1000])
        return result.stdout.strip().splitlines()

    def test_current_profiles_sources_pagination_and_reader_permissions(self):
        self.sql("""
INSERT INTO employers(source_namespace,external_id,name) VALUES
 ('greenhouse:demo','e1','Synthetic Employer'),('synthetic','e2','Fixture');
INSERT INTO vacancies(employer_id,source_namespace,listing_id,title,location,content_digest)
 VALUES (1,'greenhouse:demo','l1','Java Engineer','Berlin',repeat('a',64)),
        (1,'greenhouse:demo','l2','Backend Engineer','Vienna',repeat('b',64)),
        (2,'synthetic','l3','Fixture Role','Remote',repeat('c',64));
INSERT INTO source_items(source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 VALUES ('greenhouse:demo','s1','greenhouse:demo','l1','https://jobs.example.test/1',now(),'{}',
         'greenhouse-board-v1','v1',repeat('a',64),repeat('a',64)),
        ('greenhouse:demo','s2','greenhouse:demo','l2','https://jobs.example.test/2',now(),'{}',
         'greenhouse-board-v1','v1',repeat('b',64),repeat('b',64)),
        ('synthetic','s3','synthetic','l3','https://example.test/3',now(),'{}',
         'synthetic-v1','v1',repeat('c',64),repeat('c',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (1,1),(2,2),(3,3);
INSERT INTO verified_source_registry(platform,region,source_id,employer_domain,employer_name,
 official_page_url,evidence_url,evidence_hash,verified_at,status,active)
 VALUES ('GREENHOUSE','global','demo','example.test','Synthetic Employer',
 'https://jobs.example.test','https://jobs.example.test',repeat('f',64),now(),'VERIFIED',true);
INSERT INTO evaluation_profiles(profile_id,profile_version,criteria_hash)
 VALUES ('alex-de','v1',repeat('d',64)),('alex-at','v1',repeat('e',64));
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url) VALUES
 (1,1,repeat('a',64),'alex-de','v1',repeat('d',64),'rules-v2','SUITABLE',1,'PREFERRED',
  '["ROLE_PREFERRED"]','[]','[]','https://jobs.example.test/1'),
 (2,2,repeat('b',64),'alex-de','v1',repeat('d',64),'rules-v2','NEEDS_REVIEW',2,'RESERVE',
  '["ROLE_RESERVE"]','["WORK_ELIGIBILITY_UNCONFIRMED","SALARY_UNKNOWN"]','[]','https://jobs.example.test/2'),
 (2,2,repeat('b',64),'alex-at','v1',repeat('e',64),'rules-v2','SUITABLE',1,'PREFERRED',
  '["ROLE_PREFERRED"]','[]','[]','https://jobs.example.test/2'),
 (3,3,repeat('c',64),'alex-de','v1',repeat('d',64),'rules-v2','SUITABLE',1,'PREFERRED',
  '[]','[]','[]','https://example.test/3');
UPDATE vacancy_evaluations SET country_eligibility = 'CONFIRMED'
 WHERE (vacancy_id = 1 AND profile_id = 'alex-de') OR (vacancy_id = 2 AND profile_id = 'alex-at');
CREATE ROLE job_agent_page_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
""")
        before = json.loads(self.sql(reader.ROLE_STATE_SQL + ";")[0])
        reader.validate_role(before)
        self.assertEqual([], before["selectedColumns"])
        self.sql("CREATE ROLE job_agent_migrator LOGIN; GRANT USAGE ON SCHEMA public TO job_agent_migrator;")
        for table in reader.TABLE_COLUMNS:
            self.sql(f"ALTER TABLE public.{table} OWNER TO job_agent_migrator;")
        self.sql("ALTER TABLE public.review_feedback OWNER TO job_agent_migrator;")
        for grant in reader.GRANTS[:2]:
            self.sql(grant + ";")
        for grant in reader.GRANTS[2:]:
            self.sql("SET ROLE job_agent_migrator; " + grant + ";")
        after = json.loads(self.sql(reader.ROLE_STATE_SQL + ";")[0])
        reader.validate_role(after)
        self.assertEqual(reader.EXPECTED_COLUMNS, set(after["selectedColumns"]))
        self.assertEqual(set(reader.TABLE_COLUMNS), set(json.loads(self.sql(reader.OWNER_SQL + ";")[0])))
        self.sql("SET ROLE job_agent_migrator; GRANT SELECT "
                 "(id, vacancy_id, profile_id, evaluation_id, owner_decision, owner_reason, recorded_at) "
                 "ON public.review_feedback TO job_agent_page_reader;")
        def query(profile, limit, offset):
            statement = page.SQL.replace(":profile", f"'{profile}'").replace(":limit", str(limit))
            statement = statement.replace(":offset", str(offset))
            return self.sql("SET ROLE job_agent_page_reader; " + statement + ";")
        def quality(profile):
            statement = page.QUALITY_SQL.replace(":profile", f"'{profile}'")
            return self.sql("SET ROLE job_agent_page_reader; " + statement + ";")[0].split("|")
        de = query("alex-de", 20, 0)
        at = query("alex-at", 20, 0)
        self.assertEqual(1, len(de))
        self.assertEqual(1, len(at))
        self.assertIn("Java Engineer", de[0])
        self.assertIn("Backend Engineer", at[0])
        self.assertEqual(0, len(query("alex-de", 1, 1)))
        initial_de = quality("alex-de")
        self.assertEqual(["2", "2", "1"], initial_de[:3])
        self.assertEqual({"COUNTRY_UNCONFIRMED": 1, "SHORTLISTED": 1}, json.loads(initial_de[3]))
        self.assertEqual({"WORK_ELIGIBILITY_UNCONFIRMED": 1, "SALARY_UNKNOWN": 1},
                         json.loads(initial_de[5]))
        self.assertEqual("0", initial_de[6])  # No owner decision is not an application outcome.
        self.assertEqual(["2", "1", "1"], quality("alex-at")[:3])
        self.assertEqual("", quality("alex-de")[12])  # No previous registered profile version.
        self.assertEqual({"greenhouse-board-v1": 2}, json.loads(initial_de[23]))
        self.sql("""
INSERT INTO employers(source_namespace,external_id,name)
 VALUES ('linkedin-unverified-job','123456','Employer unknown');
INSERT INTO vacancies(employer_id,source_namespace,listing_id,title,content_digest)
 VALUES (3,'linkedin','123456','Java Engineer',repeat('f',64));
INSERT INTO source_items(id,source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 OVERRIDING SYSTEM VALUE VALUES (1000,'linkedin:gmail','message-1:123456','linkedin','123456',
 'https://www.linkedin.com/jobs/view/123456/',TIMESTAMPTZ '2026-11-18 10:00:00+00',
 '{}','linkedin-alert-v1',
 'linkedin-normalization-v1',repeat('f',64),repeat('f',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (4,1000);
INSERT INTO gmail_alert_messages(message_id,observed_at,recognized_cards,unsupported_links,status,completed_at)
 VALUES ('message-1',TIMESTAMPTZ '2026-11-18 10:00:00+00',1,0,'COMPLETE',now());
INSERT INTO gmail_alert_cards(message_id,linkedin_job_id,source_item_id,vacancy_id,outcome,
 advertised_direction,direction_evidence)
 VALUES ('message-1','123456',1000,4,'CREATED','GERMANY','Berlin, Germany');
""")
        linkedin = page.LINKEDIN_SQL.replace(":profile", "'alex-at'").replace(":limit", "20")
        linkedin = linkedin.replace(":offset", "0")
        observed = self.sql("SET ROLE job_agent_page_reader; " + linkedin + ";")
        self.assertEqual(1, len(observed))
        self.assertIn("Java Engineer", observed[0])
        self.assertIn("GERMANY", observed[0])
        self.assertIn("Berlin, Germany", observed[0])
        self.assertNotIn("123456", "".join(quality("alex-at")))  # Not a confirmed recommendation.
        self.sql("""
INSERT INTO source_items(id,source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 OVERRIDING SYSTEM VALUE VALUES (1001,'linkedin:gmail','message-2:123456','linkedin','123456',
 'https://www.linkedin.com/jobs/view/123456/',TIMESTAMPTZ '2026-11-18 11:00:00+00',
 '{}','linkedin-alert-v1',
 'linkedin-normalization-v1',repeat('e',64),repeat('e',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (4,1001);
INSERT INTO gmail_alert_messages(message_id,observed_at,recognized_cards,unsupported_links,status,completed_at)
 VALUES ('message-2',TIMESTAMPTZ '2026-11-18 11:00:00+00',1,0,'COMPLETE',now());
INSERT INTO gmail_alert_cards(message_id,linkedin_job_id,source_item_id,vacancy_id,outcome,
 advertised_direction,direction_evidence)
 VALUES ('message-2','123456',1001,4,'UNCHANGED','REMOTE_EUROPE','Remote Europe');
""")
        latest = self.sql("SET ROLE job_agent_page_reader; " + linkedin + ";")
        self.assertEqual(1, len(latest))
        self.assertIn("Java Engineer", latest[0])
        self.assertIn("REMOTE_EUROPE", latest[0])
        self.sql("""
INSERT INTO source_items(id,source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 OVERRIDING SYSTEM VALUE VALUES (1002,'linkedin:gmail','message-3:123456','linkedin','123456',
 'https://www.linkedin.com/jobs/view/123456/',TIMESTAMPTZ '2026-11-18 09:00:00+00',
 '{}','linkedin-alert-v1','linkedin-normalization-v1',repeat('d',64),repeat('d',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (4,1002);
INSERT INTO gmail_alert_messages(message_id,observed_at,recognized_cards,unsupported_links,status,completed_at)
 VALUES ('message-3',TIMESTAMPTZ '2026-11-18 09:00:00+00',1,0,'COMPLETE',now());
INSERT INTO gmail_alert_cards(message_id,linkedin_job_id,source_item_id,vacancy_id,outcome,
 advertised_direction,direction_evidence)
 VALUES ('message-3','123456',1002,4,'UNCHANGED','UNKNOWN',NULL);
""")
        out_of_order = self.sql("SET ROLE job_agent_page_reader; " + linkedin + ";")
        self.assertEqual(1, len(out_of_order))
        self.assertIn("REMOTE_EUROPE", out_of_order[0])
        self.assertIn("Remote Europe", out_of_order[0])
        self.sql("CREATE ROLE job_agent_feedback_writer LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION; "
                 "GRANT CONNECT ON DATABASE job_agent TO job_agent_feedback_writer; "
                 "GRANT USAGE ON SCHEMA public TO job_agent_feedback_writer;")
        for table, columns in feedback.WRITER_SELECT.items():
            self.sql("SET ROLE job_agent_migrator; GRANT SELECT (" + ", ".join(columns) +
                     f") ON public.{table} TO job_agent_feedback_writer;")
        self.sql("SET ROLE job_agent_migrator; GRANT INSERT (" + ", ".join(feedback.WRITER_INSERT) +
                 ") ON public.review_feedback TO job_agent_feedback_writer; "
                 "GRANT USAGE ON SEQUENCE public.review_feedback_id_seq TO job_agent_feedback_writer;")

        def submit(evaluation, vacancy, profile, decision, reason, request_id=None, version="v1"):
            identity = request_id or str(uuid.uuid4())
            statement = page.INSERT_FEEDBACK_SQL
            for name, value in {"request": f"'{identity}'", "profile": f"'{profile}'",
                                "vacancy": str(vacancy), "evaluation": str(evaluation),
                                "version": f"'{version}'", "revision": f"'{('a' if vacancy == 1 else 'b') * 64}'",
                                "decision": f"'{decision}'", "reason": "NULL" if reason is None else f"'{reason}'"}.items():
                statement = statement.replace(":" + name, value)
            self.sql("SET ROLE job_agent_feedback_writer; " + statement + ";")
            return identity

        request = submit(1, 1, "alex-de", "INTERESTED", None)
        submit(1, 1, "alex-de", "INTERESTED", None, request)
        self.assertEqual(["1"], self.sql("SELECT count(*) FROM review_feedback;"))
        submit(1, 1, "alex-at", "INTERESTED", None)  # Forged profile/evaluation combination.
        self.assertEqual(["1"], self.sql("SELECT count(*) FROM review_feedback;"))
        submit(1, 1, "alex-de", "NOT_INTERESTED", "TECH_STACK")
        submit(3, 2, "alex-at", "NEED_MORE_INFO", "SALARY_UNKNOWN")
        self.assertEqual(["3"], self.sql("SELECT count(*) FROM review_feedback;"))
        with self.assertRaisesRegex(AssertionError, "review_feedback_reason"):
            self.sql("SET ROLE job_agent_feedback_writer; INSERT INTO review_feedback "
                     "(request_id,vacancy_id,evaluation_id,profile_id,profile_version,"
                     "vacancy_revision,owner_decision,owner_reason) VALUES "
                     f"('{uuid.uuid4()}',1,1,'alex-de','v1',repeat('a',64),'INTERESTED','SALARY');")
        self.assertEqual(["3"], self.sql("SELECT count(*) FROM review_feedback;"))
        self.assertIn("NOT_INTERESTED", query("alex-de", 20, 0)[0])
        self.assertIn("NEED_MORE_INFO", query("alex-at", 20, 0)[0])
        self.assertEqual(["false", "false", "false"], self.sql("SELECT "
            "has_table_privilege('job_agent_page_reader','public.review_feedback','INSERT')::text, "
            "has_table_privilege('job_agent_feedback_writer','public.review_feedback','UPDATE')::text, "
            "has_column_privilege('job_agent_feedback_writer','public.source_items','raw_payload','SELECT')::text;"
            )[0].split("|"))
        diagnostic = self.sql("SET ROLE job_agent_page_reader; " +
                              page.YIELD_SQL.replace(":profile", "'alex-de'")
                              .replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(1, len(diagnostic))
        self.assertTrue(all("greenhouse:demo" in row for row in diagnostic))
        self.sql("""
INSERT INTO evaluation_profiles(profile_id,profile_version,criteria_hash,first_seen_at)
 VALUES ('alex-de','v2',repeat('f',64),now() + interval '1 second');
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url)
 VALUES (1,1,repeat('a',64),'alex-de','v2',repeat('f',64),'rules-v2','NEEDS_REVIEW',1,
 'PREFERRED','[]','["SALARY_UNKNOWN"]','[]','https://jobs.example.test/1');
UPDATE vacancy_evaluations SET country_eligibility = 'CONFIRMED'
 WHERE profile_id = 'alex-de' AND profile_version = 'v2';
""")
        updated = query("alex-de", 20, 0)
        self.assertEqual(1, len(updated))  # Do not mix v1 into a partially evaluated v2 shortlist.
        self.assertIn("v2", updated[0])
        self.assertIn("NOT_INTERESTED", updated[0])  # Latest owner judgment remains visible after reevaluation.
        versions = quality("alex-de")
        self.assertEqual("v1", versions[12])
        self.assertEqual(["2", "1", "2", "1"], versions[14:18])
        self.assertEqual("0", versions[18])  # Feedback was not recorded for both evaluation versions.
        submit(1, 1, "alex-de", "INTERESTED", None)  # Historical v1 is no longer the displayed evaluation.
        self.assertEqual(["3"], self.sql("SELECT count(*) FROM review_feedback;"))
        self.sql("""
INSERT INTO verified_source_registry(platform,region,source_id,employer_domain,employer_name,
 official_page_url,evidence_url,evidence_hash,verified_at,status,active)
 VALUES ('LEVER','global','demo','example.test','Synthetic Employer',
 'https://jobs.example.test','https://jobs.example.test',repeat('e',64),now(),'VERIFIED',true);
INSERT INTO source_items(source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 VALUES ('lever:global:demo','s4','lever:global:demo','l4',
 'https://jobs.example.test/4',now(),'{}','lever-postings-v1','v1',repeat('d',64),repeat('a',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (1,4);
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url,country_eligibility)
 VALUES (1,4,repeat('d',64),'alex-de','v2',repeat('f',64),'rules-v2','SUITABLE',1,
 'PREFERRED','["PREFERRED_ROLE"]','["SALARY_UNKNOWN"]','[]','https://jobs.example.test/4',
 'CONFIRMED');
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url)
 VALUES (1,4,repeat('d',64),'alex-at','v1',repeat('e',64),'rules-v2','EXCLUDED',9,
 'PREFERRED','["EXPLICIT_COUNTRY_EXCLUSION"]','["SALARY_UNKNOWN"]','[]',
 'https://jobs.example.test/4');
""")
        shared = self.sql("SET ROLE job_agent_page_reader; " +
                          page.YIELD_SQL.replace(":profile", "'alex-de'")
                          .replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(2, len(shared))
        rows = {parts[0:2][0] + "|" + parts[0:2][1]: parts
                for parts in (row.split("|") for row in shared)}
        self.assertEqual(["2", "2", "0", "0"], rows["greenhouse:demo|alex-de"][3:7])
        self.assertEqual(["1", "1", "1", "0"], rows["lever:global:demo|alex-de"][3:7])
        self.assertEqual("1", rows["lever:global:demo|alex-de"][9])
        self.assertEqual("0", rows["greenhouse:demo|alex-de"][9])
        self.assertEqual("{}", rows["greenhouse:demo|alex-de"][11])
        self.assertEqual('{"SALARY_UNKNOWN": 1}', rows["lever:global:demo|alex-de"][11])
        austria = self.sql("SET ROLE job_agent_page_reader; " +
                           page.YIELD_SQL.replace(":profile", "'alex-at'")
                           .replace(":limit", "20").replace(":offset", "0") + ";")
        at_rows = {parts[0]: parts for parts in (row.split("|") for row in austria)}
        self.assertEqual(["1", "1"], at_rows["lever:global:demo"][5:7])
        self.assertEqual('{"EXPLICIT_COUNTRY_EXCLUSION": 1}', at_rows["lever:global:demo"][10])
        self.assertTrue(all(parts[-2] == "1" for parts in rows.values()))
        de_quality, at_quality = quality("alex-de"), quality("alex-at")
        self.assertEqual(["2", "0", "0"], de_quality[:3])
        self.assertEqual({"UNEVALUATED": 2}, json.loads(de_quality[3]))
        self.assertEqual("1", de_quality[6])  # Latest DE decision, not both historical rows.
        self.assertEqual([{"decision": "NOT_INTERESTED", "reason": "TECH_STACK", "count": 1}],
                         json.loads(de_quality[7]))
        self.assertEqual(["2", "1", "1"], at_quality[:3])
        self.assertEqual({"SHORTLISTED": 1, "UNEVALUATED": 1}, json.loads(at_quality[3]))
        self.assertEqual("1", at_quality[6])
        self.assertEqual("1", de_quality[9])  # One cross-source vacancy, not two stored vacancies.
        self.sql("""
INSERT INTO source_items(source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 VALUES ('greenhouse:demo','s5','greenhouse:demo','l1',
 'https://jobs.example.test/1',now(),'{}','greenhouse-board-v1','v1',repeat('e',64),repeat('a',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (1,5);
""")
        self.assertEqual([], query("alex-de", 20, 0))  # Older source snapshot is not current.
        overlap = self.sql("SET ROLE job_agent_page_reader; " +
                           page.YIELD_SQL.replace(":profile", "'alex-de'")
                           .replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(2, len(overlap))
        self.assertTrue(all(row.split("|")[-2] == "1" for row in overlap))
        greenhouse = [row.split("|") for row in overlap if row.startswith("greenhouse:demo|")]
        self.assertTrue(all(row[3:5] == ["2", "2"] for row in greenhouse))
        self.assertEqual({"UNEVALUATED": 2}, json.loads(quality("alex-de")[3]))
        self.sql("UPDATE vacancy_evaluations SET decision = 'EXCLUDED', "
                 "role_classification = 'UNMATCHED', "
                 "reasons = '[\"EXPLICIT_TITLE_EXCLUSION\"]'::jsonb, "
                 "unknowns = '[\"SALARY_UNKNOWN\"]'::jsonb "
                 "WHERE vacancy_id = 2 AND profile_id = 'alex-at';")
        unmatched = quality("alex-at")
        self.assertEqual({"ROLE_UNMATCHED": 1, "UNEVALUATED": 1}, json.loads(unmatched[3]))
        self.assertEqual({"EXPLICIT_TITLE_EXCLUSION": 1}, json.loads(unmatched[4]))
        self.assertEqual({"SALARY_UNKNOWN": 1}, json.loads(unmatched[5]))
        self.sql("""
INSERT INTO vacancies(employer_id,source_namespace,listing_id,title,location,content_digest)
SELECT 1,'greenhouse:demo','cmp-' || n,'Synthetic Java ' || n,'Berlin',repeat('f',64)
FROM generate_series(1,10) n;
INSERT INTO source_items(source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
SELECT 'greenhouse:demo','cmp-' || n,'greenhouse:demo','cmp-' || n,
 'https://jobs.example.test/cmp/' || n,now(),'{}','greenhouse-board-v1','v1',
 repeat('f',64),repeat('f',64) FROM generate_series(1,10) n;
INSERT INTO vacancy_sources(vacancy_id,source_item_id)
SELECT v.id,si.id FROM vacancies v JOIN source_items si ON si.listing_id = v.listing_id
WHERE v.listing_id LIKE 'cmp-%';
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url,country_eligibility)
SELECT v.id,si.id,repeat('f',64),'alex-de',version.profile_version,version.criteria_hash,
 'rules-v2',version.decision,1,'PREFERRED','[]','[]','[]',si.original_url,'CONFIRMED'
FROM vacancies v JOIN vacancy_sources vs ON vs.vacancy_id = v.id
JOIN source_items si ON si.id = vs.source_item_id
CROSS JOIN (VALUES ('v1',repeat('d',64),'EXCLUDED'),
                   ('v2',repeat('f',64),'SUITABLE'))
  version(profile_version,criteria_hash,decision)
WHERE v.listing_id LIKE 'cmp-%';
INSERT INTO review_feedback(request_id,vacancy_id,evaluation_id,profile_id,profile_version,
 vacancy_revision,owner_decision,owner_reason)
SELECT gen_random_uuid(),e.vacancy_id,e.id,'alex-de',e.profile_version,e.vacancy_revision,
 CASE WHEN e.profile_version = 'v2' THEN 'INTERESTED' ELSE 'NOT_INTERESTED' END,
 CASE WHEN e.profile_version = 'v2' THEN NULL ELSE 'ROLE_MISMATCH' END
FROM vacancy_evaluations e JOIN vacancies v ON v.id = e.vacancy_id
WHERE v.listing_id LIKE 'cmp-%';
""")
        comparable = quality("alex-de")
        self.assertEqual(["10", "10", "10"], comparable[17:20])
        self.assertEqual(["0", "10", "0"], comparable[20:23])
        with self.assertRaises(AssertionError):
            self.sql("SET ROLE job_agent_page_reader; SELECT raw_payload FROM source_items;")
        with self.assertRaises(AssertionError):
            self.sql("SET ROLE job_agent_page_reader; DELETE FROM vacancies;")


if __name__ == "__main__":
    unittest.main()

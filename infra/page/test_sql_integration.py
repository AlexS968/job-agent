"""Exercise the page projection and reader grants against project migrations."""

import importlib.util
import json
from pathlib import Path
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
            subprocess.run(["docker", "exec", cls.container, "createdb", "-U", "postgres", "job_agent"],
                           check=True, capture_output=True)
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
 (1,1,repeat('a',64),'alex-de','v1',repeat('d',64),'rules-v1','SUITABLE',1,'PREFERRED',
  '["ROLE_PREFERRED"]','[]','[]','https://jobs.example.test/1'),
 (2,2,repeat('b',64),'alex-de','v1',repeat('d',64),'rules-v1','NEEDS_REVIEW',2,'RESERVE',
  '["ROLE_RESERVE"]','["WORK_ELIGIBILITY_UNCONFIRMED"]','[]','https://jobs.example.test/2'),
 (2,2,repeat('b',64),'alex-at','v1',repeat('e',64),'rules-v1','SUITABLE',1,'PREFERRED',
  '["ROLE_PREFERRED"]','[]','[]','https://jobs.example.test/2'),
 (3,3,repeat('c',64),'alex-de','v1',repeat('d',64),'rules-v1','SUITABLE',1,'PREFERRED',
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
        for grant in reader.GRANTS[:2]:
            self.sql(grant + ";")
        for grant in reader.GRANTS[2:]:
            self.sql("SET ROLE job_agent_migrator; " + grant + ";")
        after = json.loads(self.sql(reader.ROLE_STATE_SQL + ";")[0])
        reader.validate_role(after)
        self.assertEqual(reader.EXPECTED_COLUMNS, set(after["selectedColumns"]))
        self.assertEqual(set(reader.TABLE_COLUMNS), set(json.loads(self.sql(reader.OWNER_SQL + ";")[0])))
        def query(profile, limit, offset):
            statement = page.SQL.replace(":profile", f"'{profile}'").replace(":limit", str(limit))
            statement = statement.replace(":offset", str(offset))
            return self.sql("SET ROLE job_agent_page_reader; " + statement + ";")
        de = query("alex-de", 20, 0)
        at = query("alex-at", 20, 0)
        self.assertEqual(1, len(de))
        self.assertEqual(1, len(at))
        self.assertIn("Java Engineer", de[0])
        self.assertIn("Backend Engineer", at[0])
        self.assertEqual(0, len(query("alex-de", 1, 1)))
        diagnostic = self.sql("SET ROLE job_agent_page_reader; " +
                              page.YIELD_SQL.replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(2, len(diagnostic))
        self.assertTrue(all("greenhouse:demo" in row for row in diagnostic))
        self.sql("""
INSERT INTO evaluation_profiles(profile_id,profile_version,criteria_hash,first_seen_at)
 VALUES ('alex-de','v2',repeat('f',64),now() + interval '1 second');
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url)
 VALUES (1,1,repeat('a',64),'alex-de','v2',repeat('f',64),'rules-v1','NEEDS_REVIEW',1,
 'PREFERRED','[]','["SALARY_UNKNOWN"]','[]','https://jobs.example.test/1');
UPDATE vacancy_evaluations SET country_eligibility = 'CONFIRMED'
 WHERE profile_id = 'alex-de' AND profile_version = 'v2';
""")
        updated = query("alex-de", 20, 0)
        self.assertEqual(1, len(updated))  # Do not mix v1 into a partially evaluated v2 shortlist.
        self.assertIn("v2", updated[0])
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
 VALUES (1,4,repeat('d',64),'alex-de','v2',repeat('f',64),'rules-v1','SUITABLE',1,
 'PREFERRED','["PREFERRED_ROLE"]','["SALARY_UNKNOWN"]','[]','https://jobs.example.test/4',
 'CONFIRMED');
INSERT INTO vacancy_evaluations(vacancy_id,source_item_id,vacancy_revision,profile_id,
 profile_version,criteria_hash,policy_version,decision,preference_rank,role_classification,
 reasons,unknowns,evidence,original_url)
 VALUES (1,4,repeat('d',64),'alex-at','v1',repeat('e',64),'rules-v1','EXCLUDED',9,
 'PREFERRED','["EXPLICIT_COUNTRY_EXCLUSION"]','["SALARY_UNKNOWN"]','[]',
 'https://jobs.example.test/4');
""")
        shared = self.sql("SET ROLE job_agent_page_reader; " +
                          page.YIELD_SQL.replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(4, len(shared))
        rows = {parts[0:2][0] + "|" + parts[0:2][1]: parts
                for parts in (row.split("|") for row in shared)}
        self.assertEqual(["2", "2", "0", "0"], rows["greenhouse:demo|alex-de"][3:7])
        self.assertEqual(["1", "1", "1", "0"], rows["lever:global:demo|alex-de"][3:7])
        self.assertEqual("1", rows["lever:global:demo|alex-de"][9])
        self.assertEqual("0", rows["greenhouse:demo|alex-de"][9])
        self.assertEqual("{}", rows["greenhouse:demo|alex-de"][11])
        self.assertEqual('{"SALARY_UNKNOWN": 1}', rows["lever:global:demo|alex-de"][11])
        self.assertEqual(["1", "1"], rows["lever:global:demo|alex-at"][5:7])
        self.assertEqual('{"EXPLICIT_COUNTRY_EXCLUSION": 1}', rows["lever:global:demo|alex-at"][10])
        self.assertTrue(all(parts[-2] == "1" for parts in rows.values()))
        self.sql("""
INSERT INTO source_items(source_namespace,source_item_id,listing_namespace,listing_id,
 original_url,fetched_at,raw_payload,input_version,normalization_version,input_digest,content_digest)
 VALUES ('greenhouse:demo','s5','greenhouse:demo','l1',
 'https://jobs.example.test/1',now(),'{}','greenhouse-board-v1','v1',repeat('e',64),repeat('a',64));
INSERT INTO vacancy_sources(vacancy_id,source_item_id) VALUES (1,5);
""")
        self.assertEqual([], query("alex-de", 20, 0))  # Older source snapshot is not current.
        overlap = self.sql("SET ROLE job_agent_page_reader; " +
                           page.YIELD_SQL.replace(":limit", "20").replace(":offset", "0") + ";")
        self.assertEqual(4, len(overlap))
        self.assertTrue(all(row.split("|")[-2] == "1" for row in overlap))
        greenhouse = [row.split("|") for row in overlap if row.startswith("greenhouse:demo|")]
        self.assertTrue(all(row[3:5] == ["2", "2"] for row in greenhouse))
        with self.assertRaises(AssertionError):
            self.sql("SET ROLE job_agent_page_reader; SELECT raw_payload FROM source_items;")
        with self.assertRaises(AssertionError):
            self.sql("SET ROLE job_agent_page_reader; DELETE FROM vacancies;")


if __name__ == "__main__":
    unittest.main()

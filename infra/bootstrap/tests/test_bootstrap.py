import os
import unittest
from unittest.mock import patch

import psycopg

import bootstrap


class FakeSecrets:
    def __init__(self):
        self.values = {}

    def describe_secret(self, SecretId):
        if SecretId not in self.values:
            from botocore.exceptions import ClientError
            raise ClientError({"Error": {"Code": "ResourceNotFoundException"}}, "DescribeSecret")
        return {"Name": SecretId}

    def create_secret(self, Name, SecretString):
        if Name in self.values:
            raise AssertionError("Secret must not be overwritten")
        self.values[Name] = SecretString

    def get_secret_value(self, SecretId):
        return {"SecretString": self.values[SecretId]}


class BootstrapTest(unittest.TestCase):
    def test_scram_verifier_has_no_plaintext_and_uses_random_salt(self):
        password = "synthetic-password-for-test"
        first = bootstrap.scram_verifier(password)
        second = bootstrap.scram_verifier(password)
        self.assertTrue(first.startswith("SCRAM-SHA-256$4096:"))
        self.assertNotIn(password, first)
        self.assertNotEqual(first, second)

    @unittest.skipUnless(os.getenv("TEST_DB_HOST"), "Requires isolated PostgreSQL test container")
    def test_role_bootstrap_and_grant_finalization(self):
        host = os.environ["TEST_DB_HOST"]
        port = int(os.environ["TEST_DB_PORT"])
        admin_password = os.environ["TEST_DB_PASSWORD"]

        def local_connect(_host, user, password):
            return psycopg.connect(host=host, port=port, dbname="job_agent", user=user,
                                   password=password, autocommit=True)

        client = FakeSecrets()
        with patch.object(bootstrap, "connect", side_effect=local_connect):
            with local_connect(host, "test_admin", admin_password) as admin:
                class FailSecondSecret(FakeSecrets):
                    def create_secret(self, Name, SecretString):
                        if Name == "synthetic-app":
                            raise RuntimeError("Synthetic secret creation failure")
                        super().create_secret(Name, SecretString)

                partial = FailSecondSecret()
                with self.assertRaisesRegex(RuntimeError, "Synthetic secret creation failure"):
                    bootstrap.prepare(admin, partial, host, "synthetic-migration", "synthetic-app")
                self.assertEqual({"synthetic-migration"}, set(partial.values))
                self.assertEqual(set(), bootstrap.role_presence(admin))
                with self.assertRaisesRegex(RuntimeError, "controlled review"):
                    bootstrap.prepare(admin, partial, host, "synthetic-migration", "synthetic-app")

                bootstrap.prepare(admin, client, host, "synthetic-migration", "synthetic-app")
                with self.assertRaisesRegex(RuntimeError, "controlled review"):
                    bootstrap.prepare(admin, client, host, "synthetic-migration", "synthetic-app")
                self.assertEqual(2, len(client.values))

            import json
            migration_password = json.loads(client.values["synthetic-migration"])["password"]
            app_password = json.loads(client.values["synthetic-app"])["password"]
            with self.assertRaisesRegex(RuntimeError, "Migrations have not completed"):
                bootstrap.finalize(client, host, "synthetic-migration", "synthetic-app")
            with local_connect(host, bootstrap.MIGRATOR, migration_password) as migrator:
                with migrator.cursor() as cursor:
                    cursor.execute("CREATE TABLE public.flyway_schema_history (installed_rank integer)")
                    cursor.execute("CREATE TABLE public.synthetic_domain (id bigint generated always as identity primary key)")
            bootstrap.finalize(client, host, "synthetic-migration", "synthetic-app")
            bootstrap.finalize(client, host, "synthetic-migration", "synthetic-app")

            with local_connect(host, bootstrap.APP, app_password) as app:
                with app.cursor() as cursor:
                    cursor.execute("SELECT COUNT(*) FROM public.flyway_schema_history")
                    cursor.fetchone()
                    cursor.execute("INSERT INTO public.synthetic_domain DEFAULT VALUES")
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                        cursor.execute("INSERT INTO public.flyway_schema_history VALUES (1)")


if __name__ == "__main__":
    unittest.main()

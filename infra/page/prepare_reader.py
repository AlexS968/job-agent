"""One-time, owner-run reader bootstrap. Never prints or accepts password values."""

import base64
import hashlib
import hmac
import json
import os
import secrets
import sys

import boto3
from botocore.exceptions import ClientError

ROLE = "job_agent_page_reader"
SECRET_NAME = "job-agent-p5a-page-reader"
GRANTS = (
    "GRANT CONNECT ON DATABASE job_agent TO job_agent_page_reader",
    "GRANT USAGE ON SCHEMA public TO job_agent_page_reader",
    "GRANT SELECT (id, content_digest, input_version) ON public.source_items TO job_agent_page_reader",
    "GRANT SELECT (vacancy_id, source_item_id) ON public.vacancy_sources TO job_agent_page_reader",
    "GRANT SELECT (id, employer_id, source_namespace, title, content_digest) ON public.vacancies TO job_agent_page_reader",
    "GRANT SELECT (id, name) ON public.employers TO job_agent_page_reader",
    "GRANT SELECT (profile_id, profile_version, criteria_hash, first_seen_at) "
    "ON public.evaluation_profiles TO job_agent_page_reader",
    "GRANT SELECT (id, vacancy_id, source_item_id, profile_id, profile_version, vacancy_revision, "
    "criteria_hash, policy_version, original_url, decision, role_classification, reasons, unknowns, "
    "preference_rank, evaluated_at) ON public.vacancy_evaluations TO job_agent_page_reader",
)


def scram_verifier(password):
    """Send a SCRAM verifier rather than a plaintext password in the SQL request."""
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 4096)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    encoded = lambda value: base64.b64encode(value).decode()
    return f"SCRAM-SHA-256$4096:{encoded(salt)}${encoded(stored_key)}:{encoded(server_key)}"


def prepare(rds, secrets_manager, cluster_arn, admin_secret_arn, migrator_secret_arn):
    def run(sql, secret_arn=admin_secret_arn):
        return rds.execute_statement(resourceArn=cluster_arn, secretArn=secret_arn,
                                     database="job_agent", sql=sql)

    if run("SELECT 1 FROM pg_roles WHERE rolname = 'job_agent_page_reader'").get("records"):
        raise RuntimeError("Reader role already exists; review state without rotating credentials")
    try:
        secrets_manager.describe_secret(SecretId=SECRET_NAME)
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") != "ResourceNotFoundException":
            raise
    else:
        raise RuntimeError("Reader secret already exists; review state without rotating credentials")

    password = secrets.token_urlsafe(48)
    verifier = scram_verifier(password)
    # Only a verifier enters the SQL request; the password stays in memory until secret creation.
    run(f"CREATE ROLE {ROLE} LOGIN PASSWORD '{verifier}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION")
    for grant in GRANTS[:2]:
        run(grant)  # The database owner grants connect and schema access.
    for grant in GRANTS[2:]:
        run(grant, migrator_secret_arn)  # Flyway's migrator owns the tables.
    secrets_manager.create_secret(Name=SECRET_NAME,
                                  SecretString=json.dumps({"username": ROLE, "password": password}),
                                  Description="Read-only Job Agent vacancy page database role")


def main():
    cluster = os.environ.get("PAGE_CLUSTER_ARN")
    admin = os.environ.get("PAGE_ADMIN_SECRET_ARN")
    migrator = os.environ.get("PAGE_MIGRATOR_SECRET_ARN")
    if not cluster or not admin or not migrator:
        print("Set cluster, managed-admin and migrator secret ARNs in the environment; no values are accepted on the command line.",
              file=sys.stderr)
        return 2
    try:
        session = boto3.Session(profile_name="job-agent", region_name="us-east-1")
        prepare(session.client("rds-data"), session.client("secretsmanager"), cluster, admin, migrator)
    except Exception:
        print("Reader bootstrap stopped. Inspect role and secret metadata before any rerun; no credentials were printed.",
              file=sys.stderr)
        return 1
    print("Reader role and secret created. Review their metadata and use the secret ARN in ignored tfvars.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

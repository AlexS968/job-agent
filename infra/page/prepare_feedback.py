"""One-time reviewed bootstrap of separate read projection and feedback writer grants."""

import json
import os
import secrets
import sys

import prepare_reader as base
from botocore.exceptions import ClientError

ROLE = "job_agent_feedback_writer"
SECRET_NAME = "job-agent-p5a-feedback-writer"
READ_COLUMNS = ("id", "vacancy_id", "profile_id", "evaluation_id", "owner_decision",
                "owner_reason", "recorded_at")
WRITER_SELECT = {
    "evaluation_profiles": ("profile_id", "profile_version", "criteria_hash", "first_seen_at"),
    "vacancy_sources": ("vacancy_id", "source_item_id"),
    "vacancies": ("id", "source_namespace", "content_digest"),
    "source_items": ("id", "content_digest", "input_version"),
    "vacancy_evaluations": ("id", "vacancy_id", "source_item_id", "profile_id", "profile_version",
                            "vacancy_revision", "criteria_hash", "policy_version", "evaluated_at",
                            "decision", "role_classification", "country_evidence_policy", "country_eligibility"),
    "review_feedback": ("id", "request_id", "vacancy_id", "evaluation_id", "profile_id",
                        "profile_version", "vacancy_revision", "owner_decision", "owner_reason"),
}
WRITER_INSERT = ("request_id", "vacancy_id", "evaluation_id", "profile_id", "profile_version",
                 "vacancy_revision", "owner_decision", "owner_reason")


def execute(rds, cluster, secret, sql):
    return rds.execute_statement(resourceArn=cluster, secretArn=secret,
                                 database=base.DATABASE, sql=sql)


def secret_absent(client):
    try:
        client.describe_secret(SecretId=SECRET_NAME)
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") == "ResourceNotFoundException":
            return
        raise
    raise RuntimeError("Feedback writer secret already exists; review without rotating")


def prepare(sts, clusters, rds, secrets_client, account, cluster, admin, migrator):
    if (not account or len(account) != 12 or not account.isdigit()
            or sts.get_caller_identity().get("Account") != account):
        raise RuntimeError("Unexpected AWS account")
    if (not cluster.startswith(f"arn:aws:rds:{base.REGION}:{account}:cluster:")
            or not all(value.startswith(f"arn:aws:secretsmanager:{base.REGION}:{account}:secret:")
                       for value in (admin, migrator))):
        raise RuntimeError("Unexpected cluster or secret identity")
    found = clusters.describe_db_clusters(DBClusterIdentifier=cluster).get("DBClusters", [])
    if (len(found) != 1 or found[0].get("DBClusterArn") != cluster
            or found[0].get("Status") != "available"
            or found[0].get("HttpEndpointEnabled") is not True):
        raise RuntimeError("Unexpected database state")
    if base.one_text(execute(rds, cluster, migrator, "SELECT current_user::text")) != base.MIGRATOR:
        raise RuntimeError("Grant actor is not the table owner")
    metadata = base.one_text(execute(rds, cluster, migrator,
        "SELECT (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = "
        "'public.review_feedback'::regclass) || ':' || "
        "(SELECT version::text FROM flyway_schema_history WHERE success "
        "ORDER BY installed_rank DESC LIMIT 1)"))
    if metadata != "job_agent_migrator:20":
        raise RuntimeError("Feedback migration or table owner is unexpected")
    secret_absent(secrets_client)
    existing = base.one_text(execute(rds, cluster, admin,
        f"SELECT rolname::text FROM pg_roles WHERE rolname = '{ROLE}'"))
    if existing is not None:
        raise RuntimeError("Feedback writer role exists; inspect partial bootstrap before retry")
    reader = base.inspect_role(rds, cluster, admin)
    base.validate_role(reader)
    if reader is None or not reader["connect"] or not reader["schemaUsage"]:
        raise RuntimeError("Page reader is not ready")
    reader_columns = set(reader["selectedColumns"])
    if not base.EXPECTED_COLUMNS <= reader_columns <= base.EXPECTED_COLUMNS | {
            f"review_feedback.{column}" for column in READ_COLUMNS}:
        raise RuntimeError("Reader grants differ from reviewed baseline")

    # The reader receives only the latest-decision projection. No write privilege is added.
    execute(rds, cluster, migrator,
            "GRANT SELECT (" + ", ".join(READ_COLUMNS) + ") ON public.review_feedback TO " + base.ROLE)
    reader_after = base.inspect_role(rds, cluster, admin)
    if (reader_after is None or set(reader_after["selectedColumns"]) !=
            base.EXPECTED_COLUMNS | {f"review_feedback.{column}" for column in READ_COLUMNS}
            or reader_after["writeColumns"] != 0 or reader_after["writeTables"] != 0):
        raise RuntimeError("Reader feedback grant is incomplete; writer not created")
    password = secrets.token_urlsafe(48)
    verifier = base.scram_verifier(password)
    execute(rds, cluster, admin,
            f"CREATE ROLE {ROLE} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
            f"NOREPLICATION PASSWORD '{verifier}'")
    execute(rds, cluster, admin, f"GRANT CONNECT ON DATABASE {base.DATABASE} TO {ROLE}")
    execute(rds, cluster, admin, f"GRANT USAGE ON SCHEMA public TO {ROLE}")
    for table, columns in WRITER_SELECT.items():
        execute(rds, cluster, migrator,
                f"GRANT SELECT ({', '.join(columns)}) ON public.{table} TO {ROLE}")
    execute(rds, cluster, migrator,
            f"GRANT INSERT ({', '.join(WRITER_INSERT)}) ON public.review_feedback TO {ROLE}")
    execute(rds, cluster, migrator, f"GRANT USAGE ON SEQUENCE public.review_feedback_id_seq TO {ROLE}")
    checks = base.one_text(execute(rds, cluster, admin, f"""
        SELECT (has_column_privilege('{ROLE}', 'public.review_feedback', 'request_id', 'INSERT')
          AND has_column_privilege('{ROLE}', 'public.review_feedback', 'request_id', 'SELECT')
          AND NOT has_table_privilege('{ROLE}', 'public.review_feedback', 'UPDATE,DELETE,TRUNCATE')
          AND NOT has_table_privilege('{base.ROLE}', 'public.review_feedback', 'INSERT,UPDATE,DELETE'))::text
        """))
    if checks != "true":
        raise RuntimeError("Feedback privileges require review; secret not created")
    secret_absent(secrets_client)
    created = secrets_client.create_secret(
        Name=SECRET_NAME,
        SecretString=json.dumps({"username": ROLE, "password": password}),
        Description="Restricted Job Agent vacancy feedback writer")
    metadata = secrets_client.describe_secret(SecretId=SECRET_NAME)
    if not created.get("ARN") or metadata.get("ARN") != created["ARN"]:
        raise RuntimeError("Feedback secret creation is uncertain; inspect metadata")


def main():
    account = os.environ.get("PAGE_EXPECTED_ACCOUNT_ID")
    cluster = os.environ.get("PAGE_CLUSTER_ARN")
    admin = os.environ.get("PAGE_ADMIN_SECRET_ARN")
    migrator = os.environ.get("PAGE_MIGRATOR_SECRET_ARN")
    if not all((account, cluster, admin, migrator)):
        print("Set reviewed account, cluster, admin and migrator ARNs in the environment.", file=sys.stderr)
        return 2
    try:
        session = base.credential_session()
        prepare(session.client("sts"), session.client("rds"), session.client("rds-data"),
                session.client("secretsmanager"), account, cluster, admin, migrator)
    except KeyboardInterrupt:
        print("Interrupted; inspect grants and secret metadata before retry.", file=sys.stderr)
        return 130
    except Exception:
        print("Feedback bootstrap stopped; inspect role and secret metadata before retry.", file=sys.stderr)
        return 1
    print("Feedback writer provisioned; verify metadata and grants before enabling the API.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Fail-closed, resumable bootstrap for the dedicated Data API reader."""

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import sys

import boto3
from botocore.exceptions import ClientError

ROLE = "job_agent_page_reader"
MIGRATOR = "job_agent_migrator"
SECRET_NAME = "job-agent-p5a-page-reader"
REGION = "us-east-1"
DATABASE = "job_agent"
TABLE_COLUMNS = {
    "source_items": ("id", "source_namespace", "listing_id", "content_digest", "input_version"),
    "vacancy_sources": ("vacancy_id", "source_item_id"),
    "vacancies": ("id", "employer_id", "source_namespace", "title", "content_digest"),
    "employers": ("id", "name"),
    "evaluation_profiles": ("profile_id", "profile_version", "criteria_hash", "first_seen_at"),
    "vacancy_evaluations": ("id", "vacancy_id", "source_item_id", "profile_id", "profile_version",
                            "vacancy_revision", "criteria_hash", "policy_version", "original_url",
                            "decision", "role_classification", "reasons", "unknowns", "preference_rank",
                            "evaluated_at", "country_evidence_policy", "country_eligibility"),
    "verified_source_registry": ("platform", "region", "source_id", "status", "active"),
}
EXPECTED_COLUMNS = {f"{table}.{column}" for table, columns in TABLE_COLUMNS.items() for column in columns}
GRANTS = (
    f"GRANT CONNECT ON DATABASE {DATABASE} TO {ROLE}",
    f"GRANT USAGE ON SCHEMA public TO {ROLE}",
    *(f"GRANT SELECT ({', '.join(columns)}) ON public.{table} TO {ROLE}"
      for table, columns in TABLE_COLUMNS.items()),
)

# Metadata only: no vacancy contents or credential values enter this result.
ROLE_STATE_SQL = f"""
SELECT json_build_object(
 'login', r.rolcanlogin, 'super', r.rolsuper, 'createdb', r.rolcreatedb,
 'createrole', r.rolcreaterole, 'replication', r.rolreplication,
 'bypassrls', r.rolbypassrls,
 'memberships', (SELECT count(*) FROM pg_auth_members WHERE member = r.oid),
 'connect', has_database_privilege(r.rolname, current_database(), 'CONNECT'),
 'databaseCreate', has_database_privilege(r.rolname, current_database(), 'CREATE'),
 'schemaUsage', has_schema_privilege(r.rolname, 'public', 'USAGE'),
 'schemaCreate', has_schema_privilege(r.rolname, 'public', 'CREATE'),
 'selectedColumns', (SELECT coalesce(json_agg(c.relname || '.' || a.attname), '[]'::json)
   FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
   WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
     AND has_column_privilege(r.rolname, c.oid, a.attnum, 'SELECT')),
 'otherSelectedColumns', (SELECT count(*) FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
   WHERE n.nspname NOT IN ('public', 'pg_catalog', 'information_schema')
     AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
     AND has_column_privilege(r.rolname, c.oid, a.attnum, 'SELECT')),
 'writeColumns', (SELECT count(*) FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
   WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
     AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
     AND has_column_privilege(r.rolname, c.oid, a.attnum, 'INSERT,UPDATE,REFERENCES')),
 'writeTables', (SELECT count(*) FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
     AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
     AND has_table_privilege(r.rolname, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')),
 'tableSelectAcl', (SELECT count(*) FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   CROSS JOIN LATERAL aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
   WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
     AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
     AND acl.grantee IN (r.oid, 0) AND acl.privilege_type = 'SELECT')
)::text FROM pg_roles r WHERE r.rolname = '{ROLE}'
"""
OWNER_SQL = ("SELECT coalesce(json_agg(c.relname ORDER BY c.relname), '[]'::json)::text "
             "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
             "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') "
             "AND pg_get_userbyid(c.relowner) = 'job_agent_migrator' "
             "AND c.relname IN (" + ", ".join(f"'{table}'" for table in TABLE_COLUMNS) + ")")


def scram_verifier(password):
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 4096)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    encode = lambda value: base64.b64encode(value).decode()
    return f"SCRAM-SHA-256$4096:{encode(salt)}${encode(stored_key)}:{encode(server_key)}"


def credential_session(run=subprocess.run):
    """Capture short-lived aws-login credentials in memory for older boto3."""
    try:
        result = run(["aws", "configure", "export-credentials", "--profile", "job-agent",
                      "--format", "process"], capture_output=True, text=True, timeout=30, check=False)
        if result.returncode or len(result.stdout) > 16_384:
            raise ValueError("Unavailable")
        credentials = json.loads(result.stdout)
        expiry = datetime.fromisoformat(credentials["Expiration"].replace("Z", "+00:00"))
        if (credentials.get("Version") != 1 or expiry.tzinfo is None
                or expiry <= datetime.now(timezone.utc) + timedelta(minutes=5)
                or not all(isinstance(credentials.get(key), str) and credentials[key]
                           for key in ("AccessKeyId", "SecretAccessKey", "SessionToken"))):
            raise ValueError("Unavailable")
        return boto3.Session(aws_access_key_id=credentials["AccessKeyId"],
                             aws_secret_access_key=credentials["SecretAccessKey"],
                             aws_session_token=credentials["SessionToken"], region_name=REGION)
    except (OSError, subprocess.TimeoutExpired, KeyError, ValueError, TypeError) as error:
        raise RuntimeError("AWS CLI credential handoff failed before mutation") from error


def one_text(result):
    records = result.get("records", [])
    if not records:
        return None
    if len(records) != 1 or len(records[0]) != 1 or "stringValue" not in records[0][0]:
        raise RuntimeError("Unexpected Data API metadata response")
    return records[0][0]["stringValue"]


def inspect_role(rds, cluster, admin):
    value = one_text(rds.execute_statement(resourceArn=cluster, secretArn=admin,
                                           database=DATABASE, sql=ROLE_STATE_SQL))
    return None if value is None else json.loads(value)


def validate_role(state):
    if state is None:
        return
    flags = {"login": True, "super": False, "createdb": False, "createrole": False,
             "replication": False, "bypassrls": False, "memberships": 0,
             "databaseCreate": False, "schemaCreate": False,
             "otherSelectedColumns": 0, "writeColumns": 0, "writeTables": 0,
             "tableSelectAcl": 0}
    if any(state.get(key) != value for key, value in flags.items()):
        raise RuntimeError("Reader has unexpected privileges")
    if not isinstance(state.get("connect"), bool) or not isinstance(state.get("schemaUsage"), bool):
        raise RuntimeError("Reader role metadata is incomplete")
    columns = state.get("selectedColumns")
    if not isinstance(columns, list) or not all(isinstance(column, str) for column in columns):
        raise RuntimeError("Reader column grants are unknown")
    if not set(columns) <= EXPECTED_COLUMNS:
        raise RuntimeError("Reader has unexpected SELECT grants")


def secret_absent(client):
    try:
        client.describe_secret(SecretId=SECRET_NAME)
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") == "ResourceNotFoundException":
            return
        raise
    raise RuntimeError("Reader secret already exists; review without rotating")


def migrator_secret(client, arn):
    current = client.get_secret_value(SecretId=arn, VersionStage="AWSCURRENT")
    if current.get("ARN") != arn or not current.get("VersionId"):
        raise RuntimeError("Migrator secret identity/version is unexpected")
    try:
        fields = json.loads(current["SecretString"])
    except (KeyError, ValueError, TypeError) as error:
        raise RuntimeError("Migrator secret JSON is unsupported") from error
    if (not isinstance(fields, dict) or not isinstance(fields.get("password"), str)
            or not fields["password"] or fields.get("username", MIGRATOR) != MIGRATOR):
        raise RuntimeError("Migrator secret identity/password is unsupported")
    return current, fields


def preflight(sts, clusters, rds, client, account, cluster, admin, migrator):
    if (not account or not account.isdigit() or len(account) != 12
            or sts.get_caller_identity().get("Account") != account):
        raise RuntimeError("AWS account differs from reviewed target")
    prefix = f"arn:aws:rds:{REGION}:{account}:cluster:"
    if not cluster.startswith(prefix) or not cluster[len(prefix):]:
        raise RuntimeError("Cluster ARN differs from reviewed account/region")
    if not all(arn.startswith(f"arn:aws:secretsmanager:{REGION}:{account}:secret:")
               for arn in (admin, migrator)):
        raise RuntimeError("Secret ARNs differ from reviewed account/region")
    found = clusters.describe_db_clusters(DBClusterIdentifier=cluster).get("DBClusters", [])
    if (len(found) != 1 or found[0].get("DBClusterArn") != cluster
            or found[0].get("Engine") != "aurora-postgresql"
            or found[0].get("EngineVersion") != "17.11"
            or found[0].get("DatabaseName") != DATABASE
            or found[0].get("Status") != "available"
            or found[0].get("HttpEndpointEnabled") is not True):
        raise RuntimeError("Cluster or Data API state is unexpected")
    state = inspect_role(rds, cluster, admin)
    validate_role(state)
    owners = one_text(rds.execute_statement(resourceArn=cluster, secretArn=admin,
                                             database=DATABASE, sql=OWNER_SQL))
    if owners is None or set(json.loads(owners)) != set(TABLE_COLUMNS):
        raise RuntimeError("Required tables are not owned by the expected migrator")
    secret_absent(client)
    current, fields = migrator_secret(client, migrator)
    return state, current, fields


def ensure_migrator(rds, client, cluster, migrator, current, fields):
    if "username" not in fields:
        latest, latest_fields = migrator_secret(client, migrator)
        if latest["VersionId"] != current["VersionId"] or latest_fields != fields:
            raise RuntimeError("Migrator secret changed during preflight")
        updated = dict(fields)
        updated["username"] = MIGRATOR
        client.put_secret_value(SecretId=migrator,
                                SecretString=json.dumps(updated, separators=(",", ":")))
        confirmed, confirmed_fields = migrator_secret(client, migrator)
        if confirmed["VersionId"] == current["VersionId"] or confirmed_fields != updated:
            raise RuntimeError("Migrator secret update is uncertain")
    actual = one_text(rds.execute_statement(resourceArn=cluster, secretArn=migrator,
                                             database=DATABASE, sql="SELECT current_user::text"))
    if actual != MIGRATOR:
        raise RuntimeError("Data API did not authenticate as table owner")


def prepare(sts, clusters, rds, client, account, cluster, admin, migrator):
    state, current, fields = preflight(sts, clusters, rds, client, account, cluster, admin, migrator)
    ensure_migrator(rds, client, cluster, migrator, current, fields)

    def run(statement, secret=admin):
        rds.execute_statement(resourceArn=cluster, secretArn=secret,
                              database=DATABASE, sql=statement)

    password = secrets.token_urlsafe(48)
    verifier = scram_verifier(password)
    if state is None:
        run(f"CREATE ROLE {ROLE} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
            f"NOREPLICATION PASSWORD '{verifier}'")
    else:
        # The old password was never saved; rotate before resuming grants.
        run(f"ALTER ROLE {ROLE} PASSWORD '{verifier}'")
    for grant in GRANTS[:2]:
        run(grant)
    for grant in GRANTS[2:]:
        run(grant, migrator)
    verified = inspect_role(rds, cluster, admin)
    validate_role(verified)
    if (not verified["connect"] or not verified["schemaUsage"]
            or set(verified["selectedColumns"]) != EXPECTED_COLUMNS):
        raise RuntimeError("Reader grants incomplete; secret not created")
    secret_absent(client)
    created = client.create_secret(Name=SECRET_NAME,
                                   SecretString=json.dumps({"username": ROLE, "password": password}),
                                   Description="Read-only Job Agent vacancy page database role")
    metadata = client.describe_secret(SecretId=SECRET_NAME)
    if (not created.get("ARN") or metadata.get("ARN") != created["ARN"]
            or metadata.get("DeletedDate") is not None):
        raise RuntimeError("Reader secret creation is uncertain; inspect metadata before retry")


def main():
    account = os.environ.get("PAGE_EXPECTED_ACCOUNT_ID")
    cluster = os.environ.get("PAGE_CLUSTER_ARN")
    admin = os.environ.get("PAGE_ADMIN_SECRET_ARN")
    migrator = os.environ.get("PAGE_MIGRATOR_SECRET_ARN")
    if not all((account, cluster, admin, migrator)):
        print("Set reviewed account ID, cluster ARN, admin and migrator secret ARNs in the environment.",
              file=sys.stderr)
        return 2
    try:
        session = credential_session()
        prepare(session.client("sts"), session.client("rds"), session.client("rds-data"),
                session.client("secretsmanager"), account, cluster, admin, migrator)
    except KeyboardInterrupt:
        print("Interrupted; inspect role grants and secret version metadata before retry.", file=sys.stderr)
        return 130
    except Exception:
        print("Reader bootstrap stopped; inspect role grants and secret metadata before a reviewed retry.",
              file=sys.stderr)
        return 1
    print("Reader grants verified and dedicated secret created; verify metadata before page deployment.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

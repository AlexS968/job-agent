"""One-off, fail-closed database role bootstrap. Never print credential material."""

import base64
import hashlib
import hmac
import json
import os
import secrets
import sys

import boto3
import psycopg
from botocore.exceptions import ClientError
from psycopg import sql

MIGRATOR = "job_agent_migrator"
APP = "job_agent_app"
DATABASE = "job_agent"
CA_FILE = "/opt/bootstrap/certs/us-east-1-bundle.pem"


def scram_verifier(password):
    """Send a SCRAM verifier to PostgreSQL instead of a plaintext SQL password."""
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 4096)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    encode = lambda value: base64.b64encode(value).decode()
    return f"SCRAM-SHA-256$4096:{encode(salt)}${encode(stored_key)}:{encode(server_key)}"


def existing_secret(client, name):
    try:
        client.describe_secret(SecretId=name)
        return True
    except ClientError as error:
        if error.response.get("Error", {}).get("Code") == "ResourceNotFoundException":
            return False
        raise


def connect(host, user, password):
    return psycopg.connect(host=host, dbname=DATABASE, user=user, password=password,
                           sslmode="verify-full", sslrootcert=CA_FILE, connect_timeout=45,
                           autocommit=True)


def role_presence(connection):
    with connection.cursor() as cursor:
        cursor.execute("SELECT rolname FROM pg_roles WHERE rolname IN (%s, %s)", (MIGRATOR, APP))
        return {row[0] for row in cursor.fetchall()}


def require_fresh(connection, client, migration_secret, app_secret):
    with connection.cursor() as cursor:
        cursor.execute("SELECT to_regclass('public.flyway_schema_history')")
        if cursor.fetchone()[0] is not None:
            raise RuntimeError("Existing migration history requires controlled review")
    if role_presence(connection) or existing_secret(client, migration_secret) or existing_secret(client, app_secret):
        raise RuntimeError("Existing or partial bootstrap state requires controlled review")


def create_role(cursor, name, verifier):
    cursor.execute(sql.SQL("CREATE ROLE {} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD {}").format(
        sql.Identifier(name), sql.Literal(verifier)))


def prepare(connection, client, host, migration_secret, app_secret):
    require_fresh(connection, client, migration_secret, app_secret)
    migration_password = secrets.token_urlsafe(48)
    app_password = secrets.token_urlsafe(48)
    # External secret creation cannot share a PostgreSQL transaction. Partial results stop reruns.
    client.create_secret(Name=migration_secret, SecretString=json.dumps({"password": migration_password}))
    client.create_secret(Name=app_secret, SecretString=json.dumps({"password": app_password}))
    with connection.transaction():
        with connection.cursor() as cursor:
            create_role(cursor, MIGRATOR, scram_verifier(migration_password))
            create_role(cursor, APP, scram_verifier(app_password))
            cursor.execute(sql.SQL("GRANT CONNECT ON DATABASE {} TO {}, {}").format(
                sql.Identifier(DATABASE), sql.Identifier(MIGRATOR), sql.Identifier(APP)))
            cursor.execute(sql.SQL("GRANT USAGE, CREATE ON SCHEMA public TO {}").format(sql.Identifier(MIGRATOR)))
            cursor.execute(sql.SQL("GRANT USAGE ON SCHEMA public TO {}").format(sql.Identifier(APP)))
    with connect(host, MIGRATOR, migration_password) as migration_connection:
        with migration_connection.cursor() as cursor:
            cursor.execute(sql.SQL("ALTER DEFAULT PRIVILEGES IN SCHEMA public "
                                   "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {}").format(sql.Identifier(APP)))
            cursor.execute(sql.SQL("ALTER DEFAULT PRIVILEGES IN SCHEMA public "
                                   "GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO {}").format(sql.Identifier(APP)))
    print("Bootstrap roles and secrets created; run migrations, then finalize grants")


def secret_password(client, name):
    value = json.loads(client.get_secret_value(SecretId=name)["SecretString"])
    password = value.get("password")
    if not isinstance(password, str) or not password:
        raise RuntimeError("Role secret has no password")
    return password


def finalize(client, host, migration_secret, app_secret):
    migration_password = secret_password(client, migration_secret)
    app_password = secret_password(client, app_secret)
    with connect(host, MIGRATOR, migration_password) as connection:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute("SELECT to_regclass('public.flyway_schema_history')")
                if cursor.fetchone()[0] is None:
                    raise RuntimeError("Migrations have not completed")
                cursor.execute(sql.SQL("REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER "
                                       "ON public.flyway_schema_history FROM {}").format(sql.Identifier(APP)))
                cursor.execute(sql.SQL("GRANT SELECT ON public.flyway_schema_history TO {}").format(sql.Identifier(APP)))
    with connect(host, APP, app_password) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM public.flyway_schema_history")
            cursor.fetchone()
            cursor.execute("SELECT has_schema_privilege(current_user, 'public', 'CREATE'), "
                           "has_database_privilege(current_user, 'job_agent', 'CREATE'), "
                           "has_table_privilege(current_user, 'public.flyway_schema_history', 'INSERT')")
            if any(cursor.fetchone()):
                raise RuntimeError("Application role has unexpected DDL or migration-history write privilege")
        for statement in ("CREATE TABLE public.phase5_ddl_denial_probe (id integer)",
                          "CREATE SCHEMA phase5_ddl_denial_probe"):
            with connection.cursor() as cursor:
                cursor.execute("BEGIN")
                try:
                    cursor.execute(statement)
                except psycopg.errors.InsufficientPrivilege:
                    pass
                else:
                    raise RuntimeError("Application role unexpectedly performed DDL")
                finally:
                    cursor.execute("ROLLBACK")
    print("Migration history is read-only to app; table and schema DDL denied")


def main(mode):
    host = os.environ["DB_HOST"]
    secret_arn = os.environ["MASTER_SECRET_ARN"]
    migration_secret = os.environ["MIGRATION_SECRET_NAME"]
    app_secret = os.environ["APP_SECRET_NAME"]
    client = boto3.client("secretsmanager", region_name=os.environ["AWS_REGION"])
    if mode == "prepare":
        administrator = json.loads(client.get_secret_value(SecretId=secret_arn)["SecretString"])
        with connect(host, administrator["username"], administrator["password"]) as connection:
            prepare(connection, client, host, migration_secret, app_secret)
    elif mode == "finalize":
        finalize(client, host, migration_secret, app_secret)
    else:
        raise RuntimeError("Unsupported bootstrap mode")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise RuntimeError("Exactly one bootstrap mode is required")
        main(sys.argv[1])
    except Exception:
        print("Bootstrap failed; inspect roles, secrets and migration state before any rerun", file=sys.stderr)
        sys.exit(1)

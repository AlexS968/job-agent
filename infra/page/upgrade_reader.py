"""Apply a reviewed column-only upgrade to the existing page reader."""

import json
import os
from pathlib import Path
import runpy
import sys

import prepare_reader as reader

PHASE7_COLUMNS = {f"gmail_alert_cards.{column}"
                  for column in reader.TABLE_COLUMNS["gmail_alert_cards"]} | {"source_items.fetched_at"}
EXPECTED_COLUMNS = reader.EXPECTED_COLUMNS - PHASE7_COLUMNS - reader.SOURCE_REPORT_COLUMNS

LEGACY_REGISTRY_COLUMNS = tuple(column for column in reader.TABLE_COLUMNS["verified_source_registry"]
                                if column not in reader.SOURCE_REPORT_GRANTS["verified_source_registry"])
OLD_COLUMNS = EXPECTED_COLUMNS - {
    "source_items.source_namespace", "source_items.listing_id",
    "vacancy_evaluations.country_evidence_policy", "vacancy_evaluations.country_eligibility",
    *(f"verified_source_registry.{column}" for column in LEGACY_REGISTRY_COLUMNS),
}
ADDITIONAL_GRANTS = (
    f"GRANT SELECT (source_namespace, listing_id) ON public.source_items TO {reader.ROLE}",
    f"GRANT SELECT (country_evidence_policy, country_eligibility) "
    f"ON public.vacancy_evaluations TO {reader.ROLE}",
    f"GRANT SELECT ({', '.join(LEGACY_REGISTRY_COLUMNS)}) "
    f"ON public.verified_source_registry TO {reader.ROLE}",
)


REPORT_GRANTS = tuple(f"GRANT SELECT ({', '.join(columns)}) ON public.{table} TO {reader.ROLE}"
                      for table, columns in reader.SOURCE_REPORT_GRANTS.items())


def upgrade(sts, clusters, rds, secrets, account, cluster, migrator, report_reader=None):
    if not account or sts.get_caller_identity().get("Account") != account:
        raise RuntimeError("Unexpected account")
    if not cluster.startswith(f"arn:aws:rds:{reader.REGION}:{account}:cluster:"):
        raise RuntimeError("Unexpected cluster")
    if not migrator.startswith(f"arn:aws:secretsmanager:{reader.REGION}:{account}:secret:"):
        raise RuntimeError("Unexpected migrator secret")
    found = clusters.describe_db_clusters(DBClusterIdentifier=cluster).get("DBClusters", [])
    if (len(found) != 1 or found[0].get("DBClusterArn") != cluster
            or found[0].get("Status") != "available" or found[0].get("HttpEndpointEnabled") is not True):
        raise RuntimeError("Unexpected cluster state")
    metadata = secrets.describe_secret(SecretId=reader.SECRET_NAME)
    if metadata.get("DeletedDate") is not None:
        raise RuntimeError("Reader secret is unavailable")
    if report_reader and (metadata.get("ARN") != report_reader
                          or not report_reader.startswith(f"arn:aws:secretsmanager:{reader.REGION}:{account}:secret:")):
        raise RuntimeError("Unexpected report reader secret")
    actor = reader.one_text(rds.execute_statement(resourceArn=cluster, secretArn=migrator,
                                                   database=reader.DATABASE, sql="SELECT current_user::text"))
    if actor != reader.MIGRATOR:
        raise RuntimeError("Grant actor is not the table-owning migrator")
    state = reader.inspect_role(rds, cluster, migrator)
    reader.validate_role(state)
    if state is None or not state["connect"] or not state["schemaUsage"]:
        raise RuntimeError("Reader bootstrap is incomplete")
    columns = set(state["selectedColumns"])
    baseline = (reader.EXPECTED_COLUMNS - reader.SOURCE_REPORT_COLUMNS) if report_reader else OLD_COLUMNS
    expected = reader.EXPECTED_COLUMNS if report_reader else EXPECTED_COLUMNS
    if not baseline <= columns <= expected:
        raise RuntimeError("Reader grants differ from reviewed baseline")
    if columns != expected:
        for statement in REPORT_GRANTS if report_reader else ADDITIONAL_GRANTS:
            rds.execute_statement(resourceArn=cluster, secretArn=migrator,
                                  database=reader.DATABASE, sql=statement)
        updated = reader.inspect_role(rds, cluster, migrator)
        reader.validate_role(updated)
        if set(updated["selectedColumns"]) != expected:
            raise RuntimeError("Reader grant upgrade is incomplete")
    if report_reader:
        verify_source_report(rds, cluster, report_reader)


def verify_source_report(rds, cluster, secret):
    connection = {"resourceArn": cluster, "secretArn": secret, "database": reader.DATABASE}
    actor = reader.one_text(rds.execute_statement(**connection, sql="SELECT current_user::text"))
    if actor != reader.ROLE:
        raise RuntimeError("Source report did not authenticate as the existing page reader")
    handler = runpy.run_path(str(Path(__file__).parent / "lambda" / "handler.py"))
    for days in (7, 30):
        params = {"days": days, "geography": "ALL",
                  "cohort_limit": handler["MAX_REPORT_OBSERVATIONS"], "employer_limit": handler["MAX_REPORT_EMPLOYERS"]}
        result = rds.execute_statement(**connection, sql=handler["SOURCE_REPORT_SQL"], parameters=[
            handler["parameter"](name, value) for name, value in params.items()])
        report = json.loads(reader.one_text(result))
        if report["cohortExceeded"]:
            raise RuntimeError("Source report exceeds the bounded cohort")


def main():
    if sys.argv[1:] not in ([], ["--source-report"]):
        print("Expected no arguments or --source-report.", file=sys.stderr)
        return 2
    report_reader = os.environ.get("PAGE_READER_SECRET_ARN") if sys.argv[1:] else None
    if sys.argv[1:] and not report_reader:
        print("Set PAGE_READER_SECRET_ARN to the reviewed existing reader secret ARN.", file=sys.stderr)
        return 2
    account = os.environ.get("PAGE_EXPECTED_ACCOUNT_ID")
    cluster = os.environ.get("PAGE_CLUSTER_ARN")
    migrator = os.environ.get("PAGE_MIGRATOR_SECRET_ARN")
    if not all((account, cluster, migrator)):
        print("Set the reviewed account, cluster and migrator secret ARNs in the environment.", file=sys.stderr)
        return 2
    try:
        session = reader.credential_session()
        upgrade(session.client("sts"), session.client("rds"), session.client("rds-data"),
                session.client("secretsmanager"), account, cluster, migrator, report_reader)
    except KeyboardInterrupt:
        print("Interrupted; inspect reader grants before retry.", file=sys.stderr)
        return 130
    except Exception:
        print("Reader grant upgrade stopped; inspect role metadata before retry.", file=sys.stderr)
        return 1
    print("Source Report verified as page reader for both periods." if report_reader
          else "Phase 6A.2 reader columns verified; no credentials changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

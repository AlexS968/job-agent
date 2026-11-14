"""Add only Phase 6A.2 diagnostic columns to an existing page reader."""

import os
import sys

import prepare_reader as reader

OLD_COLUMNS = reader.EXPECTED_COLUMNS - {
    "source_items.source_namespace", "source_items.listing_id",
    "vacancy_evaluations.country_evidence_policy", "vacancy_evaluations.country_eligibility",
    *(f"verified_source_registry.{column}" for column in reader.TABLE_COLUMNS["verified_source_registry"]),
}
ADDITIONAL_GRANTS = (
    f"GRANT SELECT (source_namespace, listing_id) ON public.source_items TO {reader.ROLE}",
    f"GRANT SELECT (country_evidence_policy, country_eligibility) "
    f"ON public.vacancy_evaluations TO {reader.ROLE}",
    f"GRANT SELECT ({', '.join(reader.TABLE_COLUMNS['verified_source_registry'])}) "
    f"ON public.verified_source_registry TO {reader.ROLE}",
)


def upgrade(sts, clusters, rds, secrets, account, cluster, migrator):
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
    actor = reader.one_text(rds.execute_statement(resourceArn=cluster, secretArn=migrator,
                                                   database=reader.DATABASE, sql="SELECT current_user::text"))
    if actor != reader.MIGRATOR:
        raise RuntimeError("Grant actor is not the table-owning migrator")
    state = reader.inspect_role(rds, cluster, migrator)
    reader.validate_role(state)
    if state is None or not state["connect"] or not state["schemaUsage"]:
        raise RuntimeError("Reader bootstrap is incomplete")
    columns = set(state["selectedColumns"])
    if not OLD_COLUMNS <= columns <= reader.EXPECTED_COLUMNS:
        raise RuntimeError("Reader grants differ from reviewed baseline")
    if columns == reader.EXPECTED_COLUMNS:
        return
    for statement in ADDITIONAL_GRANTS:
        rds.execute_statement(resourceArn=cluster, secretArn=migrator,
                              database=reader.DATABASE, sql=statement)
    updated = reader.inspect_role(rds, cluster, migrator)
    reader.validate_role(updated)
    if set(updated["selectedColumns"]) != reader.EXPECTED_COLUMNS:
        raise RuntimeError("Reader grant upgrade is incomplete")


def main():
    account = os.environ.get("PAGE_EXPECTED_ACCOUNT_ID")
    cluster = os.environ.get("PAGE_CLUSTER_ARN")
    migrator = os.environ.get("PAGE_MIGRATOR_SECRET_ARN")
    if not all((account, cluster, migrator)):
        print("Set the reviewed account, cluster and migrator secret ARNs in the environment.", file=sys.stderr)
        return 2
    try:
        session = reader.credential_session()
        upgrade(session.client("sts"), session.client("rds"), session.client("rds-data"),
                session.client("secretsmanager"), account, cluster, migrator)
    except KeyboardInterrupt:
        print("Interrupted; inspect reader grants before retry.", file=sys.stderr)
        return 130
    except Exception:
        print("Reader grant upgrade stopped; inspect role metadata before retry.", file=sys.stderr)
        return 1
    print("Phase 6A.2 reader columns verified; no credentials changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

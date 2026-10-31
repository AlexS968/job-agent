#!/usr/bin/env bash
set -euo pipefail

test_network="job-agent-bootstrap-test-$$"
test_db="job-agent-bootstrap-db-$$"
export POSTGRES_PASSWORD="synthetic-bootstrap-test-only"
export TEST_DB_PASSWORD="$POSTGRES_PASSWORD"

cleanup() {
  docker rm -f "$test_db" >/dev/null 2>&1 || true
  docker network rm "$test_network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$test_network" >/dev/null
docker run -d --name "$test_db" --network "$test_network" \
  -e POSTGRES_DB=job_agent -e POSTGRES_USER=test_admin -e POSTGRES_PASSWORD \
  postgres:17.11-alpine >/dev/null
for attempt in {1..30}; do
  if docker exec "$test_db" pg_isready -U test_admin -d job_agent >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" -eq 30 ]]; then
    echo 'Isolated PostgreSQL did not become ready' >&2
    exit 1
  fi
  sleep 1
done

docker run --rm --read-only --network "$test_network" --entrypoint python \
  -e "TEST_DB_HOST=$test_db" -e TEST_DB_PORT=5432 -e TEST_DB_PASSWORD \
  -v "$PWD/infra/bootstrap/tests:/tests:ro" \
  job-agent-bootstrap:phase5b-local -m unittest discover -v -s /tests

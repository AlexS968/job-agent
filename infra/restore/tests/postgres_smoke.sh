#!/bin/sh
# Isolated PostgreSQL test with the project's actual Flyway migrations.
set -eu

network="job-agent-restore-test-$$"
server="job-agent-restore-pg-$$"
cleanup() {
    docker rm -f "$server" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
docker network create "$network" >/dev/null
docker run -d --name "$server" --network "$network" -e POSTGRES_DB=job_agent \
    -e POSTGRES_PASSWORD=synthetic_test_only postgres:17.11-alpine >/dev/null

ready=0
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if docker exec "$server" pg_isready -U postgres -d job_agent >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
done
if [ "$ready" -ne 1 ]; then echo "PostgreSQL did not become ready" >&2; exit 1; fi

./mvnw -B -DskipTests package
docker run --rm --platform linux/amd64 --network "$network" \
    -v "$PWD/target/job-agent-0.1.0-SNAPSHOT.jar:/opt/job-agent/app.jar:ro" \
    -e DB_URL="jdbc:postgresql://$server:5432/job_agent" -e DB_USER=postgres \
    -e DB_PASSWORD=synthetic_test_only --entrypoint java \
    eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 \
    -jar /opt/job-agent/app.jar

docker exec -i "$server" psql -v ON_ERROR_STOP=1 -U postgres -d job_agent >/dev/null <<'SQL'
CREATE ROLE job_agent_app LOGIN PASSWORD 'synthetic_app_only';
GRANT USAGE ON SCHEMA public TO job_agent_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO job_agent_app;
SQL

docker run --rm --platform linux/amd64 --read-only --network "$network" \
    -e TEST_HOST="$server" -e TEST_PASSWORD=synthetic_app_only \
    --entrypoint python job-agent-restore-verifier:local -c \
    'import os, psycopg, verify; c=psycopg.connect(host=os.environ["TEST_HOST"], dbname="job_agent", user="job_agent_app", password=os.environ["TEST_PASSWORD"], sslmode="disable", autocommit=True); verify.verify(c, 0); c.close()'

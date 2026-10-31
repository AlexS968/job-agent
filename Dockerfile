# Build and runtime indexes are pinned; buildx selects linux/amd64 for the Fargate target.
FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/main/ src/main/
RUN chmod +x mvnw && ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
RUN groupadd --system --gid 10001 jobagent && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin jobagent
WORKDIR /opt/job-agent
COPY --from=build --chown=10001:10001 /build/target/job-agent-0.1.0-SNAPSHOT.jar app.jar
COPY --chown=10001:10001 infra/certs/us-east-1-bundle.pem certs/us-east-1-bundle.pem
USER 10001:10001
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/opt/job-agent/app.jar"]

# amd64 image for the release-ck-22.8 connector, built from this working tree
# (includes the ALTER ... COMMENT propagation fix).
#
# Kept deliberately close to sink-connector-lightweight/Dockerfile so the image
# is a drop-in replacement for the published clickhouse_debezium_embedded one:
# same base, same entrypoint, same /app.jar and /config.yml paths.
#
# The platform is pinned here as well as on the build command, so a plain
# `docker build` on an arm64 host still produces an amd64 image. The bundled
# sink-connector-client is an x86-64 ELF binary, so amd64 is the only platform
# where it actually runs.
FROM --platform=linux/amd64 amazoncorretto:17

RUN yum update -y && yum install -y procps-ng && yum clean all

COPY sink-connector-client /sink-connector-client
COPY app.jar /app.jar

ENV JAVA_OPTS="-Dlog4jDebug=true"

# The JDWP agent that sink-connector-lightweight/Dockerfile bakes in
# (-agentlib:jdwp=...address=*:5005) is deliberately NOT here: with
# network_mode: host it is an unauthenticated remote-code-execution port.
# Add it back only for a debugging session, never on a shared host.
ENTRYPOINT ["java", "-jar", "/app.jar", "/config.yml", "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]

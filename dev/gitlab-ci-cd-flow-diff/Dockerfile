FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.6.1@sha256:975814ab8cc45184da7efe36b0595c95b2c70866bde7a8d66b0c5ab7293e1779

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

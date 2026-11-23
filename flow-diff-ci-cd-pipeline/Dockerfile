FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.15@sha256:55029ebaf48a32c4375df3242fdb063b21888259fc5c5ad8faece0d7a739ef92

USER root
RUN apk add --no-cache git=2.54.1-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 qubership-nifi-tools/qubership-nifi-flow-diff-cli-deps/target/lib /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.10@sha256:23e07d15bf555a0b7d6e27237f48c6a2736d21223194cc313e3886913a89ab6d

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 qubership-nifi-tools/qubership-nifi-flow-diff-cli-deps/target/lib /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

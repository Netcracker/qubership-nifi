FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.13@sha256:f64223849fb62c82caa684721fef7fa1b9b5ff64e2c0c2b7a622ecc2b60eb1a0

USER root
RUN apk add --no-cache git=2.54.1-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 qubership-nifi-tools/qubership-nifi-flow-diff-cli-deps/target/lib /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

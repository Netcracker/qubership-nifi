FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.12@sha256:46fc43ba4aa3265790ad7aa13c6a959d8872630b805e0fa523b43dba97521210

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

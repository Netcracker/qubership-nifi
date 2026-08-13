FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.3.7

USER root
RUN apk add --no-cache git jq curl

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

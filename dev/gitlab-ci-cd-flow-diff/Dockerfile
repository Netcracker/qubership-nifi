FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.6.0@sha256:a1963c77d6b7cce805de22ff5e957ca32aaf54d40fb4a1d0aeb7df93d50e5703

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

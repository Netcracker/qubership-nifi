FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.11@sha256:62a1461795efe40eb05b584915cf335f24a4bb6b357faf173e175d454fee48bb

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

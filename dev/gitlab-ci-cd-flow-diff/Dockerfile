FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.3.7

USER root
RUN apk add --no-cache git jq curl
COPY lib/ /opt/flow-diff/lib/
COPY nifi-flow-diff.sh /usr/local/bin/nifi-flow-diff
RUN chmod 755 /usr/local/bin/nifi-flow-diff

USER 10001

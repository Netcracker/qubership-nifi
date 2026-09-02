ARG BASE_IMAGE_VERSION='25-alpine-2.4.1'
ARG BASE_IMAGE_VERSION_SHA256='sha256:caaf4118073ca1863cf20ca351e8411981dbcc802ed3f8d4720b7c5fb59f8971'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0 curl=8.21.0-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

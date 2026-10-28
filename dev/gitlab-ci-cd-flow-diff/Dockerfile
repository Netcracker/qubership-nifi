ARG BASE_IMAGE_VERSION='25-alpine-2.5.9'
ARG BASE_IMAGE_VERSION_SHA256='sha256:0761288c4696b1830b7ed7d76e0c2ab57562eb2c78a5e7df2894ac5c933f9f09'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

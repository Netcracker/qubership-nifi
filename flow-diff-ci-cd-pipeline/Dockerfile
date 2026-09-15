ARG BASE_IMAGE_VERSION='25-alpine-2.5.0'
ARG BASE_IMAGE_VERSION_SHA256='sha256:dde157e5a55583c4b7bb71582b92118dae95ad33a3a0171b9b03db3df580b5c5'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.2-r0 curl=8.22.0-r0

USER 10001
COPY --chown=10001:10001 qubership-nifi-tools/qubership-nifi-flow-diff-cli-deps/target/lib /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

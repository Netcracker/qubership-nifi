ARG BASE_IMAGE_VERSION='25-alpine-2.3.7'
ARG BASE_IMAGE_VERSION_SHA256='sha256:577def182c22d770bba27ed663b14fff4621cf1ad99979fa3bf415e2ae756fac'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256

USER root
RUN apk add --no-cache git=2.54.0-r0 jq=1.8.1-r0 curl=8.21.0-r0

USER 10001
COPY --chown=10001:10001 lib/ /opt/flow-diff/lib/
RUN mv /opt/flow-diff/lib/qubership-nifi-flow-diff-cli-*.jar /opt/flow-diff/lib/flow-diff-cli.jar

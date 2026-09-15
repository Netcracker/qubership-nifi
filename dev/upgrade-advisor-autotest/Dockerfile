ARG BASE_IMAGE_VERSION='25-alpine-2.5.0'
ARG BASE_IMAGE_VERSION_SHA256='sha256:dde157e5a55583c4b7bb71582b92118dae95ad33a3a0171b9b03db3df580b5c5'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256
USER root
RUN apk add --no-cache jq=1.8.2-r0
# Working directory for the advisor: it writes the report and its temporary files into the current
# directory, so this must stay separate from the read-only directory holding the exports.
RUN mkdir -p /work && chown 1001:1001 /work
USER 1001
COPY --chown=1001:1001 dev/upgrade-advisor/upgradeAdvisor.sh /advisor/
WORKDIR /work
# Directory with NiFi configuration exports to analyze
VOLUME /data/export
ENTRYPOINT ["/bin/bash", "/advisor/upgradeAdvisor.sh"]

ARG BASE_IMAGE_VERSION='25-alpine-2.4.1'
ARG BASE_IMAGE_VERSION_SHA256='sha256:caaf4118073ca1863cf20ca351e8411981dbcc802ed3f8d4720b7c5fb59f8971'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256
USER root
RUN apk add --no-cache jq=1.8.2-r0
USER 1001
COPY --chown=1001:1001 dev/update-scripts-2.0/*.sh dev/update-scripts-2.0/*.json /scripts/2.0/
COPY --chown=1001:1001 dev/update-scripts-prop-2.x/*.sh dev/update-scripts-prop-2.x/*.json /scripts/2.x/
COPY --chown=1001:1001 dev/update-scripts-flow-2.x/*.sh dev/update-scripts-flow-2.x/*.json /scripts/flow-2.x/
COPY --chown=1001:1001 dev/update-scripts-autotest/*.sh /scripts/
WORKDIR /scripts
# Directory with certificates
VOLUME /tmp/certs
ENTRYPOINT ["/bin/bash", "entrypoint.sh"]

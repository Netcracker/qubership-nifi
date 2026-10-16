ARG BASE_IMAGE_VERSION='25-alpine-2.5.7'
ARG BASE_IMAGE_VERSION_SHA256='sha256:54d135e72a4045f06583b9add6673458799eb87f549df882af619ce5e37264f0'

FROM ghcr.io/netcracker/qubership-java-base:$BASE_IMAGE_VERSION@$BASE_IMAGE_VERSION_SHA256
USER root
RUN apk add --no-cache jq=1.8.2-r0
USER 1001
COPY --chown=1001:1001 dev/update-scripts-2.0/*.sh dev/update-scripts-2.0/*.json /scripts/2.0/
COPY --chown=1001:1001 dev/update-scripts-prop-2.x/*.sh dev/update-scripts-prop-2.x/*.json /scripts/2.x/
COPY --chown=1001:1001 dev/update-scripts-flow-2.x/*.sh dev/update-scripts-flow-2.x/*.json /scripts/flow-2.x/
COPY --chown=1001:1001 dev/update-scripts-ojdbc-location/*.sh /scripts/ojdbc-location/
COPY --chown=1001:1001 dev/update-scripts-autotest/*.sh /scripts/
WORKDIR /scripts
# Directory with certificates
VOLUME /tmp/certs
ENTRYPOINT ["/bin/bash", "entrypoint.sh"]

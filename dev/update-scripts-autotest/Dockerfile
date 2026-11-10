FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.12@sha256:46fc43ba4aa3265790ad7aa13c6a959d8872630b805e0fa523b43dba97521210
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

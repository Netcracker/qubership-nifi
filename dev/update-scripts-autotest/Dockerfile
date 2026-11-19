FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.15@sha256:55029ebaf48a32c4375df3242fdb063b21888259fc5c5ad8faece0d7a739ef92
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

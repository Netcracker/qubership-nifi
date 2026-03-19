FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.2.4@sha256:eb6f44dac3e2a05b8ca69a5472b8054a0d3668fd7cfb2a037dfa36a7dba8c567
USER root
RUN apk add --no-cache jq=1.8.1-r0
USER 10001
COPY --chown=10001:10001 dev/update-scripts-2.0/*.sh dev/update-scripts-2.0/*.json /scripts/2.0/
COPY --chown=10001:10001 dev/update-scripts-autotest/*.sh /scripts/
WORKDIR /scripts
# Directory with certificates
VOLUME /tmp/certs
ENTRYPOINT ["/bin/bash", "entrypoint.sh"]

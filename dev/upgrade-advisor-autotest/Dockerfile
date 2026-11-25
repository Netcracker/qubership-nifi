FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.6.0@sha256:a1963c77d6b7cce805de22ff5e957ca32aaf54d40fb4a1d0aeb7df93d50e5703
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

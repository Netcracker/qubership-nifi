FROM ghcr.io/netcracker/qubership-java-base:25-alpine-2.5.14@sha256:4b5993f0ce809e772ba819076a7cfb1d8c7a6bb87b5bc031604a457f1fe87d23
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

# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.0.2@sha256:028b335259ba14a32c91c11657ebff5f3c7172fd5edb26cb612fc7ec82f9c698 AS base
LABEL org.opencontainers.image.authors="qubership.org"

USER root
#add jq:
RUN apk add --no-cache \
    jq=1.8.1-r0 \
    python3=3.12.12-r0 \
    py3-pip=25.1.1-r0

ENV NIFI_BASE_DIR=/opt/nifi
ENV NIFI_HOME=$NIFI_BASE_DIR/nifi-current
ENV NIFI_TOOLKIT_HOME=$NIFI_BASE_DIR/nifi-toolkit-current
ENV NIFI_PID_DIR=${NIFI_HOME}/run
ENV NIFI_LOG_DIR=${NIFI_HOME}/logs
ENV HOME=${NIFI_HOME}

#add /home/nifi symlink
RUN mkdir -p /opt/nifi/nifi-home-dir \
    && ln -s /opt/nifi/nifi-home-dir /home/nifi \
    && chown 10001:0 /opt/nifi/nifi-home-dir \
    && chmod 775 /opt/nifi/nifi-home-dir \
    && mkdir -p /opt/java/openjdk/lib/security \
    && mkdir -p /etc/ssl/certs/java/ \
    && ln -s /app/volumes/certs/java/cacerts /etc/ssl/certs/java/cacerts \
    && adduser --disabled-password \
        --gecos "" \
        --home "${NIFI_HOME}" \
        --ingroup "root" \
        --no-create-home \
        --uid 10001 \
        $USER_NAME

USER 10001

FROM apache/nifi:2.6.0@sha256:81a6217fe9c8fcbd6bdf2a9609f0068ea0a8a49e3477bf9a196e51b208486324 AS nifi

RUN chmod 750 $NIFI_BASE_DIR/nifi-toolkit-current/bin/*.sh

FROM base
LABEL org.opencontainers.image.authors="qubership.org"

USER 10001

COPY --chown=10001:0 --from=nifi $NIFI_BASE_DIR/ $NIFI_BASE_DIR/

#cloud-migration-db-redis-services/cloud-migration-db-redis-service-nar/target/cloud-migration-db-redis-service-nar-*.nar \
COPY --chown=10001:0 qubership-bundle/qubership-nifi-processors-nar/target/migration-nifi-processors-*.nar \
    qubership-nifi-bulk-redis-service/qubership-nifi-bulk-redis-api-nar/target/qubership-nifi-bulk-redis-api-nar-*.nar \
    qubership-nifi-bulk-redis-service/qubership-nifi-bulk-redis-nar/target/qubership-nifi-bulk-redis-nar-*.nar \
    qubership-services/qubership-service-api-nar/target/qubership-service-api-nar-*.nar \
    qubership-services/qubership-service-nar/target/qubership-service-nar-*.nar \
    qubership-nifi-lookup-services/qubership-nifi-lookup-service-nar/target/qubership-nifi-lookup-service-nar-*.nar \
    qubership-nifi-db-bundle/qubership-nifi-db-processors-nar/target/qubership-nifi-db-processors-nar-*.nar \
    $NIFI_HOME/lib/

RUN mkdir -p $NIFI_HOME/persistent_data \
    && mkdir -p $NIFI_HOME/persistent_data/conf \
    && mkdir -p $NIFI_HOME/persistent_data/database_repository \
    && mkdir -p $NIFI_HOME/persistent_data/flowfile_repository \
    && mkdir -p $NIFI_HOME/persistent_data/content_repository \
    && mkdir -p $NIFI_HOME/persistent_data/provenance_repository \
    && mkdir -p $NIFI_HOME/persistent_data/state \
    && mkdir -p $NIFI_HOME/persistent_data/state/local \
    && chown 10001:0 -R $NIFI_HOME/persistent_data \
    && chmod 774 -R $NIFI_HOME/persistent_data \
    && chmod 775 $NIFI_HOME/logs \
    && cp $NIFI_HOME/lib/logback*.jar ${NIFI_TOOLKIT_HOME}/lib/ \
    && rm -rf ${NIFI_TOOLKIT_HOME}/lib/slf4j-simple*.jar \
    && mkdir -p $NIFI_HOME/nifi-config-template \
    && mkdir -p $NIFI_HOME/nifi-config-template-custom \
    && mkdir -p $NIFI_HOME/conf \
    && chmod 774 $NIFI_HOME/conf \
    && mkdir -p $NIFI_HOME/logs \
    && chmod 774 $NIFI_HOME/logs \
    && mkdir -p $NIFI_HOME/run \
    && chmod 774 $NIFI_HOME/run \
    && mkdir -p $NIFI_HOME/work \
    && chmod 774 $NIFI_HOME/work \
    && mkdir -p $NIFI_HOME/extensions \
    && chmod 775 $NIFI_HOME/extensions \
    && mkdir -p $NIFI_HOME/python_extensions \
    && chmod 775 $NIFI_HOME/python_extensions

COPY --chown=10001:0 ./nifi-scripts/*.sh ./nifi-scripts/*.json $NIFI_BASE_DIR/scripts/
COPY --chown=10001:0 ./scripts $NIFI_HOME/scripts/
COPY --chown=10001:0 ./nifi-config/logback.xml ${NIFI_TOOLKIT_HOME}/classpath/

COPY --chown=10001:0 --from=nifi $NIFI_BASE_DIR/nifi-current/conf $NIFI_BASE_DIR/nifi-current/nifi-config-template
COPY --chown=10001:0 ./nifi-config/bootstrap.conf ./nifi-config/config-client-template.json $NIFI_HOME/nifi-config-template-custom/

ARG NIFI_VERSION='2.5.0'

RUN chmod 774 $NIFI_BASE_DIR/scripts/*.sh \
    && mkdir -p $NIFI_HOME/utility-lib \
    && mkdir -p $NIFI_HOME/auxiliary-cp \
    && ln -s $NIFI_HOME/work/nar/extensions/nifi-poi-nar-$NIFI_VERSION.nar-unpacked/NAR-INF/bundled-dependencies $NIFI_HOME/auxiliary-cp/nifi-poi-nar-cp

COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-misc-deps/target/lib/ojdbc8-*.jar ${NIFI_HOME}/lib/ojdbc8.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-misc-deps/target/lib/orai18n-*.jar ${NIFI_HOME}/lib/orai18n.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-misc-deps/target/lib/postgresql-*.jar ${NIFI_HOME}/lib/postgresql.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-h2-deps-2-1-210/target/lib/h2-*.jar qubership-nifi-deps/qubership-nifi-h2-deps-2-1-214/target/lib/h2-*.jar qubership-nifi-deps/qubership-nifi-h2-deps-2-2-220/target/lib/h2-*.jar ${NIFI_HOME}/utility-lib/

COPY --chown=10001:0 qubership-consul/qubership-consul-application/target/qubership-consul-application*.jar $NIFI_HOME/utility-lib/qubership-consul-application.jar

USER 10001:0
WORKDIR $NIFI_HOME

VOLUME ${NIFI_HOME}/conf \
        ${NIFI_HOME}/logs \
        ${NIFI_HOME}/run \
        ${NIFI_HOME}/work

EXPOSE 8080 8443 10000 8000
ENTRYPOINT ["../scripts/start.sh"]
HEALTHCHECK NONE

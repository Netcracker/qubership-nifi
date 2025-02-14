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

FROM alpine/java:21-jdk as base
LABEL org.opencontainers.image.authors="qubership.org"

USER root
#add jq:
RUN apk add --no-cache \
    jq=1.7.1-r0 \
    bash=5.2.26-r0 \
    curl=8.12.1-r0

#add /home/nifi symlink
RUN mkdir -p /opt/nifi/nifi-home-dir \
    && ln -s /opt/nifi/nifi-home-dir /home/nifi \
    && chown 10001:0 /opt/nifi/nifi-home-dir \
    && chmod 775 /opt/nifi/nifi-home-dir

ENV NIFI_BASE_DIR /opt/nifi
ENV NIFI_HOME $NIFI_BASE_DIR/nifi-current
ENV NIFI_TOOLKIT_HOME $NIFI_BASE_DIR/nifi-toolkit-current
ENV NIFI_PID_DIR=${NIFI_HOME}/run
ENV NIFI_LOG_DIR=${NIFI_HOME}/logs
ENV HOME=${NIFI_HOME}

USER 10001

FROM apache/nifi:1.28.1 as nifi

RUN sed -i "s:-Xmx256m}:-Xmx640m}:g" $NIFI_BASE_DIR/nifi-toolkit-current/bin/encrypt-config.sh \
    && chmod 750 $NIFI_BASE_DIR/nifi-toolkit-current/bin/*.sh \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-web-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-core-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-aop-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-context-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-beans-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-expression-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-jdbc-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-tx-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-vault-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/spring-security-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/ant*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/netty-codec*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/xmlsec-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/h2-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/protobuf-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/esapi-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/nifi-toolkit-flowanalyzer-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/nifi-site-to-site-client-*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/velocity-engine-core*.jar \
    && rm -rf $NIFI_BASE_DIR/nifi-toolkit-current/lib/testng*.jar
    
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
    && chmod 775 $NIFI_HOME/extensions

COPY --chown=10001:0 ./nifi-scripts/*.sh $NIFI_BASE_DIR/scripts/
COPY --chown=10001:0 ./scripts $NIFI_HOME/scripts/
COPY --chown=10001:0 ./nifi-config/logback.xml ${NIFI_TOOLKIT_HOME}/classpath/

COPY --chown=10001:0 --from=nifi $NIFI_BASE_DIR/nifi-current/conf $NIFI_BASE_DIR/nifi-current/nifi-config-template
COPY --chown=10001:0 ./nifi-config/bootstrap.conf ./nifi-config/config-client-template.json $NIFI_HOME/nifi-config-template-custom/

ARG NIFI_VERSION='1.28.1'

RUN chmod 774 $NIFI_BASE_DIR/scripts/start.sh \
    && chmod 774 $NIFI_BASE_DIR/scripts/qubership-secure.sh \
    && mkdir -p $NIFI_HOME/utility-lib \
    && mkdir -p $NIFI_HOME/auxiliary-cp \
    && ln -s $NIFI_HOME/work/nar/extensions/nifi-poi-nar-$NIFI_VERSION.nar-unpacked/NAR-INF/bundled-dependencies $NIFI_HOME/auxiliary-cp/nifi-poi-nar-cp

COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-db-deps/target/lib/ojdbc8-*.jar ${NIFI_HOME}/lib/ojdbc8.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-db-deps/target/lib/orai18n-*.jar ${NIFI_HOME}/lib/orai18n.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-db-deps/target/lib/postgresql-*.jar ${NIFI_HOME}/lib/postgresql.jar
COPY --chown=10001:0 qubership-nifi-deps/qubership-nifi-h2-deps-2-1-210/target/lib/h2-*.jar qubership-nifi-deps/qubership-nifi-h2-deps-2-1-214/target/lib/h2-*.jar qubership-nifi-deps/qubership-nifi-h2-deps-2-2-220/target/lib/h2-*.jar ${NIFI_HOME}/utility-lib/

COPY --chown=10001:0 qubership-consul/qubership-consul-application/target/qubership-consul-application*.jar $NIFI_HOME/utility-lib/qubership-consul-application.jar

USER 10001:10001
WORKDIR $NIFI_HOME

VOLUME ${NIFI_HOME}/conf
VOLUME ${NIFI_HOME}/logs
VOLUME ${NIFI_HOME}/run
VOLUME ${NIFI_HOME}/work

EXPOSE 8080 8443 10000 8000
ENTRYPOINT ["../scripts/start.sh"]

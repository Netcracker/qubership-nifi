## Run in tls mode

1. Create `docker-compose.yaml` file with contents.  
Where:  
	1.`<tls-certificate directory>` - path to the local directory where the certificates for the TLS connection will be located.  
	2.`<nifi-conf directory>` - path to the local directory where the conf files for NiFi will be located.  
	3.`<nifi-registry-conf directory>` - path to the local directory where the conf files for NiFi-Registry will be located.  
	4.`<nifi-registry-database>` - path to the local directory where the database for NiFi-Registry will be located.  
	5.`<nifi-registry-flow>` -  - path to the local directory where information about flow for NiFi-Registry will be located.  
	
```YAML
services:
  nifi-toolkit:
    image: apache/nifi-toolkit:1.28.1
    container_name: nifi-toolkit
    entrypoint: /bin/sh
    command: -c "if [ ! -d /data/target/nifi/localhost ]; then /opt/nifi-toolkit/nifi-toolkit-1.28.1/bin/tls-toolkit.sh standalone -n \"localhost\" --subjectAlternativeNames \"nifi\" -C \"CN=admin, OU=NIFI\" -P ${TRUSTSTORE_PASSWORD_NIFI} -S ${KEYSTORE_PASSWORD_NIFI} -o /data/target/nifi; else return 0; fi && mkdir /data/target/nifi-registry && cp /data/target/nifi/nifi-cert.pem /data/target/nifi-registry && cp /data/target/nifi/nifi-key.key /data/target/nifi-registry  && /opt/nifi-toolkit/nifi-toolkit-1.28.1/bin/tls-toolkit.sh standalone -n \"localhost\" --subjectAlternativeNames \"nifi-registry\" -C \"CN=admin, OU=NIFI\" -P ${TRUSTSTORE_PASSWORD_NIFI_REG} -S ${KEYSTORE_PASSWORD_NIFI_REG} -o /data/target/nifi-registry"
    volumes:
      - tls-certificate:/data
    environment:
      - KEYSTORE_PASSWORD_NIFI=${KEYSTORE_PASSWORD_NIFI}
      - TRUSTSTORE_PASSWORD_NIFI=${TRUSTSTORE_PASSWORD_NIFI}
      - KEYSTORE_PASSWORD_NIFI_REG=${KEYSTORE_PASSWORD_NIFI_REG}
      - TRUSTSTORE_PASSWORD_NIFI_REG=${TRUSTSTORE_PASSWORD_NIFI_REG}
  nifi:
    image: ghcr.io/netcracker/nifi:latest
    container_name: qubership-nifi
    depends_on:
      nifi-toolkit:
        condition: service_completed_successfully
    ports:
      - "8080:8080"
      - "8443:8443"
    volumes:
      - nifi-conf:/opt/nifi/nifi-current/conf
      - tls-certificate:/data
    environment:
      - AUTH=tls
      - NIFI_WEB_HTTP_PORT=8080
      - NIFI_WEB_HTTPS_PORT=8443
      - NIFI_WEB_HTTP_HOST=0.0.0.0
      - NIFI_WEB_PROXY_HOST=localhost
      - NIFI_SENSITIVE_PROPS_KEY=${NIFI_SENSITIVE_PROPS_KEY}
      - KEYSTORE_PATH=/target/nifi/localhost/keystore.jks
      - KEYSTORE_TYPE=jks
      - KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD_NIFI}
      - TRUSTSTORE_PATH=/target/nifi/localhost/truststore.jks
      - TRUSTSTORE_TYPE=jks
      - TRUSTSTORE_PASSWORD=${TRUSTSTORE_PASSWORD_NIFI}
      - INITIAL_ADMIN_IDENTITY=CN=admin, OU=NIFI
      - NAMESPACE=local
      - CONSUL_ENABLED=true
      - CONSUL_URL=consul:8500
      - CONSUL_CONFIG_JAVA_OPTIONS=
    restart: unless-stopped
  nifi-registry:
    image: ghcr.io/netcracker/nifi-registry:latest
    container_name: qubership-nifi-registry
    depends_on:
      nifi-toolkit:
        condition: service_completed_successfully
    ports:
      - "18080:18080"
      - "18443:18443"
    volumes:
      - nifi-registry-conf:/opt/nifi-registry/nifi-registry-current/conf
      - nifi-registry-database:/opt/nifi-registry/nifi-registry-current/database
      - nifi-registry-flow:/opt/nifi-registry/nifi-registry-current/flow_storage
      - tls-certificate:/data
    environment:
      - AUTH=tls
      - NIFI_REGISTRY_WEB_HTTP_PORT=18080
      - NIFI_REGISTRY_WEB_HTTPS_PORT=18443
      - NIFI_REGISTRY_WEB_HTTP_HOST=0.0.0.0
      - KEYSTORE_PATH=/target/nifi-registry/localhost/keystore.jks
      - KEYSTORE_TYPE=jks
      - KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD_NIFI_REG}
      - TRUSTSTORE_PATH=/target/nifi-registry/localhost/truststore.jks
      - TRUSTSTORE_TYPE=jks
      - TRUSTSTORE_PASSWORD=${TRUSTSTORE_PASSWORD_NIFI_REG}
      - INITIAL_ADMIN_IDENTITY=CN=admin, OU=NIFI
    restart: unless-stopped
  consul:
    image: hashicorp/consul:1.20
    ports:
      - 127.0.0.1:18500:8500
    container_name: consul    
volumes:    
  tls-certificate:
    driver: local
    driver_opts:
      o: bind
      type: none
      device: <tls-certificate directory>
      
  nifi-conf:
    driver: local
    driver_opts:
      o: bind
      type: none
      device: <nifi-conf directory>
      
  nifi-registry-conf:
    driver: local
    driver_opts:
      o: bind
      type: none
      device: <nifi-registry-conf directory>
      
  nifi-registry-database:
    driver: local
    driver_opts:
      o: bind
      type: none
      device: <nifi-registry-database>
      
  nifi-registry-flow:
    driver: local
    driver_opts:
      o: bind
      type: none
      device: <nifi-registry-flow>
```

2.Create `.env` file with contents in the same directory as `docker-compose.yaml`.  
Where:  
	1.`<keystore nifi>` - Key password to use for NiFi.  
	2.`<trustore nifi>` - Keystore password to use for NiFi.  
	3.`<keystore nifi-registry>` - Key password to use for NiFi-Registry.  
	4.`<trustore nifi-registry>` - Keystore password to use for NiFi-Registry.  
	5.`<some key value>` - should be replaced with some string at least 12 characters in length  
	
```YAML	
KEYSTORE_PASSWORD_NIFI=<keystore nifi>
TRUSTSTORE_PASSWORD_NIFI=<trustore nifi>
KEYSTORE_PASSWORD_NIFI_REG=<keystore nifi-registry>
TRUSTSTORE_PASSWORD_NIFI_REG=<trustore nifi-registry>
NIFI_SENSITIVE_PROPS_KEY=<some key value>
```

3. Run `docker compose -f docker-compose.yaml create`
4. Run `docker compose -f docker-compose.yaml start`
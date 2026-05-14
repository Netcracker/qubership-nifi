#!/bin/bash -e

echo "Waiting for zookeeper to start (sleep for 10s):"
sleep 10
echo "Creating /local znode..."
zkCli.sh -client-configuration /conf/client.cfg -server zookeeper:2182 create /local
echo "Setting ACL for /local znode..."
zkCli.sh -client-configuration /conf/client.cfg -server zookeeper:2182 setAcl /local x509:CN=nifi-0:crw,x509:CN=nifi-1:crw,x509:CN=nifi-2:crw

echo "Creating /local/nifi znode..."
zkCli.sh -client-configuration /conf/client.cfg -server zookeeper:2182 create /local/nifi
echo "Setting ACL for /local/nifi znode..."
zkCli.sh -client-configuration /conf/client.cfg -server zookeeper:2182 setAcl /local/nifi x509:CN=nifi-0:crw,x509:CN=nifi-1:crw,x509:CN=nifi-2:crw

echo "Finished successfully!!!"

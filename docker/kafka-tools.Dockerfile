FROM eclipse-temurin:21-jre

WORKDIR /opt/kafka-tools
COPY kafka-tools/build/install/kafka-tools/ /opt/kafka-tools/

ENTRYPOINT ["/opt/kafka-tools/bin/kafka-tools"]

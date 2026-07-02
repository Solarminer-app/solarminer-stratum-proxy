FROM eclipse-temurin:21-jdk
WORKDIR /app
EXPOSE 8080
COPY build/libs/solarminer-stratum-proxy-0.0.1-SNAPSHOT.jar solarminer-stratum-proxy.jar
ENTRYPOINT ["java","-jar","solarminer-stratum-proxy.jar"]
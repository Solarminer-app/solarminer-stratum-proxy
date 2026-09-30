FROM eclipse-temurin:21-jdk
WORKDIR /app
EXPOSE 8080
COPY build/libs/stratum-proxy-1.0.1.jar solarminer-stratum-proxy.jar
ENTRYPOINT ["java","-jar","solarminer-stratum-proxy.jar"]

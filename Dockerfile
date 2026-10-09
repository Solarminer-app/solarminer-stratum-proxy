FROM eclipse-temurin:21-jdk
WORKDIR /app
EXPOSE 8090/tcp 8091/udp 8092/udp 3333/tcp 3334/tcp 3335/tcp 3336/tcp 3337/tcp 3338/tcp 3339/tcp
COPY build/libs/stratum-proxy-1.0.1.jar solarminer-stratum-proxy.jar
ENTRYPOINT ["java","-jar","solarminer-stratum-proxy.jar"]

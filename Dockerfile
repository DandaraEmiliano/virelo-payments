FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/virelo-payments-0.1.0.jar /app/virelo.jar
ENV VIRELO_ADDRESS=0.0.0.0 \
    VIRELO_DB_URL="jdbc:h2:mem:virelo;DB_CLOSE_DELAY=-1" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55.0", "-jar", "/app/virelo.jar"]

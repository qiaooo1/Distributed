FROM eclipse-temurin:17-jre
ARG SERVICE
WORKDIR /app
COPY --chown=10001:10001 ${SERVICE}/target/${SERVICE}.jar /app/service.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/service.jar"]

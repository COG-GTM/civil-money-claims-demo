FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn/settings.xml /root/.m2/settings.xml
COPY legacy-claim-store/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY legacy-claim-store/src ./src
RUN mvn -B -q -DskipTests package
RUN mvn -B -q dependency:copy -Dartifact=com.datadoghq:dd-java-agent:1.55.0 -DoutputDirectory=/agent

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/app.jar /app/app.jar
COPY --from=build /agent/dd-java-agent-1.55.0.jar /app/dd-java-agent.jar
COPY observability /app/observability
COPY seed /app/seed
ENV PORT=8081 DD_SERVICE=legacy-claim-store DD_ENV=demo DD_TRACE_ENABLED=false LOGGING_CONFIG=file:/app/observability/java/logback-spring.xml
USER app
EXPOSE 8081
ENTRYPOINT ["/bin/sh", "/app/observability/java/start.sh"]

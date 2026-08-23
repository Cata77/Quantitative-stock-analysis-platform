FROM gradle:9.1.0-jdk25 AS builder

WORKDIR /workspace
COPY settings.gradle build.gradle ./
RUN mkdir -p \
    gateway-service \
    auth-service \
    portfolio-service \
    market-data-producer \
    scoring-service \
    screener-service
COPY database-migrations/build.gradle ./database-migrations/build.gradle
COPY database-migrations/src ./database-migrations/src

RUN gradle :database-migrations:bootJar --no-daemon \
    && cp database-migrations/build/libs/database-migrations-*.jar /tmp/database-migrations.jar

FROM eclipse-temurin:25.0.2_10-jre

WORKDIR /app
COPY --from=builder /tmp/database-migrations.jar ./database-migrations.jar

ENTRYPOINT ["java", "-jar", "/app/database-migrations.jar"]
CMD ["migrate"]

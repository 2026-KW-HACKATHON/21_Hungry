FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
RUN install -d -o app -g app /data/private-files
COPY --from=builder /workspace/build/libs/21-hungry-be.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

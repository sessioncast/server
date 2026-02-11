# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -Dmaven.test.skip=true

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/relay-server-1.0.0.jar app.jar
COPY agent-tokens.json .
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

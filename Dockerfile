# Build stage
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM --platform=linux/amd64 eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/relay-server-1.0.0.jar app.jar
COPY agent-tokens.json .
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

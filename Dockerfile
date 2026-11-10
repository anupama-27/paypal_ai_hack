# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=postgres
EXPOSE 8080
# Render gives DB parts separately; build the JDBC URL unless DATABASE_URL is already set.
ENTRYPOINT ["sh","-c","export DATABASE_URL=\"${DATABASE_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}\"; exec java -XX:MaxRAMPercentage=75 -jar app.jar"]

FROM maven:3.9.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/apache-rerank-load-test-1.0.0.jar /app/apache-rerank-load-test.jar
ENTRYPOINT ["java", "-jar", "/app/apache-rerank-load-test.jar"]

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
COPY cluster.properties.example ./cluster.properties
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/distributed-cache-1.0.0.jar app.jar
COPY --from=build /app/cluster.properties .
EXPOSE 6001 6002 6003
ENV CACHE_MODE=single
ENV CACHE_NODE_ID=1
ENTRYPOINT ["java", "-jar", "app.jar", "cluster.properties"]

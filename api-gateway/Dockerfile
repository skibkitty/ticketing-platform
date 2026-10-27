FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# The root pom lists every module, and Maven reads them all before -pl narrows
# the build to this one, so each module's pom.xml has to be here even though
# only this module and common are compiled.
COPY common/pom.xml common/
COPY reservation-service/pom.xml reservation-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY api-gateway/pom.xml api-gateway/
COPY platform-tests/pom.xml platform-tests/
COPY common ./common
COPY api-gateway ./api-gateway
RUN mvn -B -pl api-gateway -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

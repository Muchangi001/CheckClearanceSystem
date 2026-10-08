# Maven comes from the image, not mvnw: the wrapper untars Maven at build time,
# and some hosted builders' sandboxes can't (SnapDeploy: "Function not implemented").
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src src
# Tests need Docker (Testcontainers), so they run outside the image build.
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/cts-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cts
USER cts
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "org.springframework.boot.loader.launch.JarLauncher"]

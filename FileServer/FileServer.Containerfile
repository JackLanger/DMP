FROM eclipse-temurin:26-jre
ARG VERSION
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

COPY FileServer/build/libs/FileServer-$VERSION*.jar /app/FileServer.jar
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "FileServer.jar"]
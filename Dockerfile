FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src/Server.java .
RUN javac Server.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/*.class .
COPY public public
CMD ["java", "Server"]
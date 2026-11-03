# Render builds this on every push to main. Tests run during the build, so a
# failing test leaves the previous version running. See "部署到 Render" in README.md.
FROM eclipse-temurin:17-jdk AS build
ARG EZMJ_COMMIT=b3c09ec096a680c1b2075f13cfdce3cc53c217c2
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates
RUN git clone https://github.com/snick911/ezmj.git /ezmj && git -C /ezmj checkout "$EZMJ_COMMIT"
WORKDIR /app
COPY src src
COPY test test
RUN mkdir -p build/classes build/test-classes \
 && javac --release 17 -encoding UTF-8 -d build/classes \
      $(find /ezmj/src -name '*.java' -not -path '*/ezmj/ui/*' -not -path '*/ezmj/Main.java') \
      $(find src -name '*.java') \
 && (cd src && find . -type f ! -name '*.java' -exec cp --parents {} ../build/classes \;) \
 && javac --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes $(find test -name '*.java') \
 && java -Dfile.encoding=UTF-8 -cp build/classes:build/test-classes ezmj.server.AllTests \
 && jar --create --file server.jar --main-class ezmj.server.EzmjServer -C build/classes .

FROM eclipse-temurin:17-jre
COPY --from=build /app/server.jar /server.jar
CMD ["java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75", "-jar", "/server.jar"]

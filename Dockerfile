ARG BUILDER_IMAGE=maven:3.9.11-eclipse-temurin-25
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre
ARG APP_VERSION=0.2.9

FROM --platform=$BUILDPLATFORM ${BUILDER_IMAGE} AS builder

WORKDIR /workspace

COPY pom.xml /workspace/pom.xml
COPY app/pom.xml /workspace/app/pom.xml
COPY rag-core-common/pom.xml /workspace/rag-core-common/pom.xml
COPY rag-trace/pom.xml /workspace/rag-trace/pom.xml
COPY rag-metadata-cacher/pom.xml /workspace/rag-metadata-cacher/pom.xml
COPY rag-query-planner/pom.xml /workspace/rag-query-planner/pom.xml
COPY rag-retrieval-engine/pom.xml /workspace/rag-retrieval-engine/pom.xml

RUN mvn -pl app -am dependency:go-offline

COPY app/src /workspace/app/src
COPY rag-core-common/src /workspace/rag-core-common/src
COPY rag-trace/src /workspace/rag-trace/src
COPY rag-metadata-cacher/src /workspace/rag-metadata-cacher/src
COPY rag-query-planner/src /workspace/rag-query-planner/src
COPY rag-retrieval-engine/src /workspace/rag-retrieval-engine/src

RUN mvn -pl app -am clean package -DskipTests

FROM --platform=$BUILDPLATFORM ${RUNTIME_IMAGE} AS extractor

ARG APP_VERSION

WORKDIR /work

COPY --from=builder /workspace/app/target/app-${APP_VERSION}-SNAPSHOT.jar /work/app.jar

RUN mkdir -p /layers \
    && java -Djarmode=layertools -jar /work/app.jar extract --destination /layers

FROM ${RUNTIME_IMAGE}

ARG APP_VERSION
LABEL org.opencontainers.image.title="rag-core-int" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.description="Internal RAG core service"

WORKDIR /app

ENV TZ=Asia/Shanghai \
    LOG_DIR=/app/logs \
    JAVA_OPTS=""

RUN mkdir -p /app/logs

COPY --from=extractor /layers/dependencies/ /app/
COPY --from=extractor /layers/spring-boot-loader/ /app/
COPY --from=extractor /layers/snapshot-dependencies/ /app/
COPY --from=extractor /layers/application/ /app/

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]

# shortlink-ai-gateway 应用镜像（构建上下文 = 仓库根目录）
#
# 多阶段构建：Maven 构建层 → layertools 分层提取 → JRE 运行层。
# 运行必需的环境变量（JWT_SECRET、ADMIN/VIEWER_PASSWORD、DB_PASSWORD 等）
# 见 README「生产部署建议」与 .env.example，镜像内不落任何密钥。
#
# IMAGE_PREFIX：基础镜像仓库前缀（直连 Docker Hub 不通的环境设为
#   docker.m.daocloud.io/library/；CI 的 GitHub Actions 直连，用默认空值）。

ARG IMAGE_PREFIX=

FROM ${IMAGE_PREFIX}maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 提取 Spring Boot 分层 jar（Boot 3.3 默认分层）
FROM ${IMAGE_PREFIX}eclipse-temurin:17-jre-jammy AS extract
WORKDIR /app
COPY --from=build /build/target/shortlink-ai-gateway.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM ${IMAGE_PREFIX}eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --system --uid 1001 appuser
COPY --from=extract --chown=appuser /app/dependencies/ ./
COPY --from=extract --chown=appuser /app/spring-boot-loader/ ./
COPY --from=extract --chown=appuser /app/snapshot-dependencies/ ./
COPY --from=extract --chown=appuser /app/application/ ./
USER appuser
EXPOSE 8010
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.JarLauncher"]

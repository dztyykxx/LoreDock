# LoreDock 后端镜像
# 构建阶段使用项目 Maven Wrapper 锁定版本（enforcer 限定 Maven [3.9.12,3.9.13)，
# 不能用镜像自带 Maven 版本），运行阶段使用 glibc JRE（onnxruntime 原生库依赖 glibc，禁用 alpine/musl）。

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷贝依赖声明，利用 Docker 层缓存避免每次全量下载依赖
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline || true

# 再拷贝源码，跳过测试打包（测试由本地/CI 独立执行）
COPY src ./src
RUN ./mvnw -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# healthcheck 依赖 curl；unzip 用于解包运行；--no-install-recommends 保持镜像精简
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

# fat jar 名称由 Spring Boot repackage 固定；glob 避免硬编码版本号
COPY --from=build /build/target/*.jar /app/app.jar

# 必须解包运行（关键）：
# Spring Boot fat jar 通过 JarLauncher 暴露 nested: URL 协议，而框架 ClasspathSkillRegistry
# 只处理 jar:/file: 协议，导致它枚举不到 agent-skills（project-qa 问答技能）→ 问答报 AGENT_SKILL_UNAVAILABLE。
# 解包后用普通目录类路径运行，getResource("agent-skills") 走 file: 分支，与本地开发一致。
RUN mkdir -p /app/app-root \
    && cd /app/app-root \
    && unzip -q /app/app.jar \
    && rm /app/app.jar

EXPOSE 8080
# 数据与模型相对路径以 WORKDIR=/app 为锚点（见 deploy/.env.production.example）
ENTRYPOINT ["sh", "-c", "exec java -cp '/app/app-root/BOOT-INF/classes:/app/app-root/BOOT-INF/lib/*' io.github.loredock.LoreDockApplication"]
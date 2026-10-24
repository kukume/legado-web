# 前端、后端两个构建阶段固定在构建机的原生平台上运行（产物与平台无关），
# 构建多平台镜像时只有最后的运行镜像按目标平台生成，不用在模拟环境里跑 npm 和 Gradle

# ---------- 前端 ----------
FROM --platform=$BUILDPLATFORM node:26-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN chmod -R a+x node_modules/.bin || true
RUN npm run build:fast

# ---------- 后端 ----------
FROM --platform=$BUILDPLATFORM gradle:9-jdk21 AS server
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY third_party ./third_party
COPY server ./server
COPY --from=web /web/dist ./server/src/main/resources/static
RUN gradle :server:buildFatJar --no-daemon -q

# ---------- 运行 ----------
FROM eclipse-temurin:21-jre
# 镜像里不带浏览器：webView:true 的书源、远程浏览器需要浏览器时，另外启动一个浏览器容器，
# 在后台“系统设置”中填写它的调试地址（见 docker-compose.yml 中的 browser 服务）
WORKDIR /app
COPY --from=server /src/server/build/libs/legado-web.jar /app/legado-web.jar
ENV LEGADO_DATA_DIR=/data \
    PORT=8080
# 检查更新用：构建时间（GitHub Actions 传入的 SOURCE_DATE_EPOCH，与镜像的 created 相同）和镜像名。
# 本地构建不传时为空，服务显示为开发版，不检查更新
ARG SOURCE_DATE_EPOCH
ARG LEGADO_IMAGE
ENV LEGADO_BUILD_TIME=${SOURCE_DATE_EPOCH} \
    LEGADO_IMAGE=${LEGADO_IMAGE}
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/legado-web.jar"]

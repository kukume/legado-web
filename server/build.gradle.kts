plugins {
    kotlin("jvm")
    id("io.ktor.plugin")
    application
}

group = "io.legado.web"
version = "0.1.0"

application {
    mainClass.set("io.legado.web.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

val ktorVersion = "3.6.0"
val exposedVersion = "1.5.1"

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")
    // 开放接口文档（Swagger UI 静态资源，离线可用）
    implementation("org.webjars:swagger-ui:5.33.1")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Book source engine (ported from Legado)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.htmlunit:htmlunit-core-js:5.3.0-legado.4")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.5")
    implementation("com.jayway.jsonpath:json-path:3.0.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("cn.hutool:hutool-crypto:5.8.48")
    implementation("org.apache.commons:commons-text:1.15.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core:0.2.17")

    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}

ktor {
    fatJar {
        archiveFileName.set("legado-web.jar")
    }
}

// 将前端构建产物打包进 jar：./gradlew :server:buildFatJar -PwithWeb
val webDist = rootProject.layout.projectDirectory.dir("web/dist")
if (project.hasProperty("withWeb")) {
    val buildWeb by tasks.registering(Exec::class) {
        workingDir = rootProject.file("web")
        val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
        commandLine(npm, "run", "build")
    }
    tasks.processResources {
        dependsOn(buildWeb)
        from(webDist) { into("static") }
    }
}

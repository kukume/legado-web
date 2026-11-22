plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    application
}

group = "io.legado.web"
version = "0.1.0"

application {
    mainClass.set("io.legado.web.ApplicationKt")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.swagger.ui)

    // 数据库
    implementation(libs.bundles.database)
    implementation(libs.bcrypt)

    // 书源引擎（移植自阅读）
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.htmlunit.core.js)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.jsoupxpath)
    implementation(libs.json.path)
    implementation(libs.gson)
    implementation(libs.hutool.crypto)
    implementation(libs.commons.text)
    implementation(libs.brotli.dec)
    implementation(libs.quick.transfer.core)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// SQLite 驱动用 JNI 加载原生库：JDK 24 起需要允许原生访问，否则启动时警告，将来的版本会直接禁止。
// 写在 jar 的清单里，java -jar 启动时生效（Docker、手动运行都不用另加参数）
tasks.withType<Jar>().configureEach {
    manifest { attributes("Enable-Native-Access" to "ALL-UNNAMED") }
}

application {
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

ktor {
    fatJar {
        archiveFileName.set("legado-web.jar")
    }
}

// 将前端构建产物打包进 jar：./gradlew :server:buildFatJar -PwithWeb
val webDist = rootProject.layout.projectDirectory.dir("web/dist")
if (project.hasProperty("withWeb")) {
    val buildWeb = tasks.register<Exec>("buildWeb") {
        workingDir = rootProject.file("web")
        val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
        commandLine(npm, "run", "build")
    }
    tasks.processResources {
        dependsOn(buildWeb)
        from(webDist) { into("static") }
    }
}

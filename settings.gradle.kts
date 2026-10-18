pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // LegadoTeam 定制的 JS 引擎（htmlunit-core-js 5.3.0-legado.4），随仓库附带
        maven(rootDir.resolve("third_party/maven")) {
            content { includeModule("org.htmlunit", "htmlunit-core-js") }
        }
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "legado-web"
include(":server")

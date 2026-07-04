// =====================================================================
// Scroff Server - Gradle Settings (Kotlin DSL)
// 国内镜像配置：阿里云 + 腾讯云，避免拉取慢 / 超时
// =====================================================================

pluginManagement {
    repositories {
        // 阿里云 Gradle 插件镜像（替换 plugins.gradle.org）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 阿里云 Spring 插件
        maven { url = uri("https://maven.aliyun.com/repository/spring-plugin") }
        // 阿里云公共仓库
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 腾讯云 Spring 插件（备用）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // 兜底：官方源（被国内镜像挡住时用）
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS：项目里 build.gradle.kts 再写 repositories 也以本文件为准
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 阿里云 Spring 仓库（最常用，含 spring-boot-starter-*）
        maven { url = uri("https://maven.aliyun.com/repository/spring") }
        // 阿里云公共仓库（聚合 central + jcenter）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 阿里云中央仓库
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        // 阿里云 Google 仓库（万一用到）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // 华为云镜像
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        // 腾讯云 Maven 公共仓库（备用）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        // 兜底
        mavenCentral()
    }
}

rootProject.name = "scroff-server"

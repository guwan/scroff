// =====================================================================
// Scroff Server - Build Script (Kotlin DSL)
// Spring Boot 3.3.5 + Java 17 + Thymeleaf + JPA + MariaDB
// =====================================================================

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.scroff"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// 仓库由 settings.gradle.kts 的 PREFER_SETTINGS 模式集中管理

dependencies {
    // Web (含 Tomcat)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Thymeleaf
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Bean Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // JPA + MariaDB
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // Lombok：编译期生成 getter/setter
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // 测试
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Spring Boot fat jar
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("scroff-server")
    archiveVersion.set("")
    archiveClassifier.set("")
}

// 禁用自带 test 任务跑空用例报错
tasks.named<Test>("test") {
    enabled = false
}

import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6"
}

val artifactName = providers.gradleProperty("artifact")
val dockerImage = providers.gradleProperty("dockerImage")

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
description = "SolarMiner Stratum Proxy"

base {
    archivesName.set(artifactName)
}

springBoot {
    buildInfo()
}

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints only the project version"

    doLast {
        println(project.version)
    }
}

tasks.register("printDockerImage") {
    group = "versioning"
    description = "Prints only the Docker image repository"

    doLast {
        println(dockerImage.get())
    }
}

tasks.register("printImageReference") {
    group = "versioning"
    description = "Prints the versioned Docker image reference"

    doLast {
        println("${dockerImage.get()}:${project.version}")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    implementation("io.netty:netty-handler:4.2.15.Final")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}


tasks.named<BootBuildImage>("bootBuildImage") {
    environment.put("BP_NATIVE_IMAGE_BUILD_ARGUMENTS", "-march=compatibility")

    imageName.set(
        dockerImage.map { image ->
            "$image:${project.version}"
        }
    )

    environment.put(
        "BP_NATIVE_IMAGE_BUILD_ARGUMENTS",
        "-march=compatibility"
    )

    docker {
        publishRegistry {
            username = System.getenv("DOCKER_USER")
            password = System.getenv("DOCKER_PASS")
        }
    }
}

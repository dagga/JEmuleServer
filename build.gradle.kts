import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "org.jemule"
version = "0.3.0"

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    "implementation"("org.slf4j:slf4j-api:2.0.9")
    "implementation"("ch.qos.logback:logback-classic:1.5.13")
    "implementation"("com.h2database:h2:2.3.232")
    "implementation"("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    "implementation"("io.github.resilience4j:resilience4j-all:2.2.0")
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.1")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("JEmuleServer")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "org.jemule.Main"
    }
}

// Make 'build' task depend on 'shadowJar'
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// Disable the default 'jar' task
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the JEmuleServer"
    mainClass.set("org.jemule.Main")
    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    val mainSourceSet = sourceSets.getByName("main")
    classpath = mainSourceSet.runtimeClasspath
    jvmArgs("-Djava.net.preferIPv4Stack=true")
}

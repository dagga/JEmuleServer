// ...existing code...

plugins {
    java
    id("com.gradleup.shadow") version "8.3.10"
    jacoco
}

group = "org.jemule"
version = "1.2.21"

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
    "testImplementation"("org.mockito:mockito-core:5.8.0")
    "testImplementation"("org.mockito:mockito-junit-jupiter:5.8.0")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test").configure {
    jvmArgs("--add-modules=jdk.httpserver")
    useJUnitPlatform {
        excludeTags("benchmark")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

// Measures the server with a large database (./gradlew benchmark -Pfiles=1000000), outside the regular test suite
tasks.register<Test>("benchmark") {
    description = "Runs the large database benchmarks."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    maxHeapSize = (project.findProperty("heap") ?: "2g").toString()
    // A low-cost server has 1 or 2 vCPUs
    jvmArgs("-XX:ActiveProcessorCount=" + (project.findProperty("cpus") ?: "2").toString())
    systemProperty("jemule.benchmark.files", (project.findProperty("files") ?: "1000000").toString())
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

tasks.named<JacocoReport>("jacocoTestReport").configure {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(false)
        csv.required.set(false)
        html.required.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Ensure server.properties (project root) is included in the jar resources
tasks.named("processResources") {
    doLast {
        copy {
            from(rootProject.file("server.properties"))
            into("$buildDir/resources/main")
        }
    }
}

tasks.named<Jar>("shadowJar").configure {
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
    jvmArgs("-Djava.net.preferIPv4Stack=true", "--add-modules=jdk.httpserver", "-XX:ThreadPriorityPolicy=1")
}
// ...existing code...

plugins {
    java
    id("com.gradleup.shadow") version "8.3.10"
    jacoco
    // Builds the admin web panel (admin-ui/) with a Node.js and npm it downloads: nothing to install
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "org.jemule"
version = "1.6.1"

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    "implementation"("org.slf4j:slf4j-api:2.0.9")
    "implementation"("ch.qos.logback:logback-classic:1.5.13")
    "implementation"("com.h2database:h2:2.3.232")
    // JDBC only: the X DevAPI and its protobuf dependency are not used
    "implementation"("com.mysql:mysql-connector-j:9.2.0") {
        exclude(group = "com.google.protobuf")
    }
    "implementation"("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    "implementation"("io.github.resilience4j:resilience4j-all:2.2.0")
    // The file index (FileIndex), on disk next to the database
    "implementation"("org.apache.lucene:lucene-core:10.5.2")
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.1")
    "testImplementation"("org.mockito:mockito-core:5.20.0")
    "testImplementation"("org.mockito:mockito-junit-jupiter:5.20.0")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test").configure {
    jvmArgs("--add-modules=jdk.httpserver")
    // MySQL backend tests, skipped without a server: -Pmysql.url=jdbc:mysql://127.0.0.1:3306/jemule_test
    // -Pmysql.user=... -Pmysql.password=... (the tables of that database are dropped)
    listOf("url", "user", "password").forEach { key ->
        project.findProperty("mysql.$key")?.let { systemProperty("jemule.test.mysql.$key", it.toString()) }
    }
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
    // H2 database (a copy is used) compared with its MySQL import: -Ph2=/path/to/jemule_db.mv.db
    project.findProperty("h2")?.let { systemProperty("jemule.equivalence.h2", it.toString()) }
    listOf("url", "user", "password").forEach { key ->
        project.findProperty("mysql.$key")?.let { systemProperty("jemule.test.mysql.$key", it.toString()) }
    }
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

// Admin web panel: a Next.js app exported as static files (admin-ui/out), served by AdminWebPanel from the JAR.
// Node.js and npm are pinned and downloaded into .gradle/ (nodejs, npm); the packages come from package-lock.json.
node {
    download.set(true)
    version.set("24.22.2")
    npmVersion.set("11.19.1")
    npmInstallCommand.set("ci")
    nodeProjectDir.set(file("admin-ui"))
}

tasks.named<com.github.gradle.node.npm.task.NpmInstallTask>("npmInstall").configure {
    // The lockfile npm writes in node_modules stands for the whole tree, much faster to check than every file
    nodeModulesOutputFilter {
        include(".package-lock.json")
    }
}

val buildAdminUi = tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildAdminUi") {
    description = "Builds the admin web panel (Next.js static export) into admin-ui/out."
    group = "build"
    dependsOn(tasks.named("npmInstall"))
    npmCommand.set(listOf("run", "build"))
    environment.put("NEXT_TELEMETRY_DISABLED", "1")
    inputs.dir("admin-ui/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("admin-ui/package.json", "admin-ui/package-lock.json", "admin-ui/next.config.mjs",
        "admin-ui/tsconfig.json").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("admin-ui/out")
}

// Ensure server.properties (project root) is included in the jar resources
tasks.named<ProcessResources>("processResources") {
    // The exported panel, under admin-ui/ in the JAR
    from(buildAdminUi) {
        into("admin-ui")
    }
    doLast {
        copy {
            from(rootProject.file("server.properties"))
            into("$buildDir/resources/main")
        }
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").configure {
    archiveBaseName.set("JEmuleServer")
    archiveClassifier.set("")
    // H2 and MySQL both register their JDBC driver in META-INF/services/java.sql.Driver: both lines are kept
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "org.jemule.Main"
        // Lucene maps the index files through java.lang.foreign: no warning at each start
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
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
    // Development configuration (git-ignored), server.properties otherwise
    if (rootProject.file("server.properties.local").exists()) args("server.properties.local")
}
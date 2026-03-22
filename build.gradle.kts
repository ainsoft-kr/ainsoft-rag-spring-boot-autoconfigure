import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.signing.SigningExtension
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun Project.propertyOrEnv(propertyName: String, envName: String): String? =
    (findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("kapt") version "2.3.0"
    id("org.jetbrains.dokka") version "1.9.20"
    `java-library`
    id("maven-publish")
    id("signing")
}

group = (findProperty("projectGroup") as String?) ?: "com.ainsoft.rag"
version = (findProperty("projectVersion") as String?) ?: "0.1.0-SNAPSHOT"

val engineVersion = (findProperty("engineVersion") as String?) ?: version.toString()

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    api("com.ainsoft.rag:core:$engineVersion")
    api("com.ainsoft.rag:chunkers:$engineVersion")
    api("com.ainsoft.rag:embeddings-api:$engineVersion")
    implementation("com.ainsoft.rag:parsers-api:$engineVersion")
    api("com.ainsoft.rag:stats-cache-spi:$engineVersion")
    api("com.ainsoft.rag:stats-cache-file:$engineVersion")
    runtimeOnly("com.ainsoft.rag:reranker-onnx:$engineVersion")

    api("org.springframework.boot:spring-boot-autoconfigure:4.0.4")
    compileOnly("org.springframework.boot:spring-boot-starter-web:4.0.4")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa:4.0.4")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("org.apache.lucene:lucene-core:10.3.2")
    implementation("org.apache.lucene:lucene-analysis-nori:10.3.2")
    implementation("org.apache.lucene:lucene-queryparser:10.3.2")
    implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    kapt("org.springframework.boot:spring-boot-configuration-processor:4.0.4")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.4")
    testImplementation("org.springframework.boot:spring-boot-starter-web:4.0.4")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val frontendDir = layout.projectDirectory.dir("frontend")
val frontendBuildOutput = frontendDir.dir("build")
val bundledAdminUiDir = layout.projectDirectory.dir("src/main/resources/META-INF/resources/rag-admin")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
val skipFrontendBuild = providers.gradleProperty("skipFrontendBuild")
    .map(String::toBoolean)
    .orElse(false)

sourceSets {
    main {
        resources {
            if (!skipFrontendBuild.get()) {
                exclude("META-INF/resources/rag-admin/**")
            }
        }
    }
}

val frontendNpmInstall = tasks.register<Exec>("frontendNpmInstall") {
    group = "frontend"
    description = "Install the rag-admin SvelteKit frontend dependencies."
    workingDir(frontendDir.asFile)
    commandLine(npmExecutable, "ci")
    onlyIf { !skipFrontendBuild.get() }
    inputs.files(
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json")
    )
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Build the rag-admin SvelteKit frontend."
    dependsOn(frontendNpmInstall)
    workingDir(frontendDir.asFile)
    commandLine(npmExecutable, "run", "build")
    onlyIf { !skipFrontendBuild.get() }
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("static"))
    inputs.files(
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json"),
        frontendDir.file("svelte.config.js"),
        frontendDir.file("vite.config.js"),
        frontendDir.file("jsconfig.json")
    )
    outputs.dir(frontendBuildOutput)
}

tasks.register<Sync>("deployFrontend") {
    group = "frontend"
    description = "Copy the built rag-admin frontend into src/main/resources."
    dependsOn(buildFrontend)
    into(bundledAdminUiDir)
    from(frontendBuildOutput)
}

tasks.named<ProcessResources>("processResources") {
    if (!skipFrontendBuild.get()) {
        dependsOn(buildFrontend)
        from(frontendBuildOutput) {
            into("META-INF/resources/rag-admin")
        }
    }
}

tasks.named("dokkaHtml").configure {
    group = "documentation"
}

tasks.register("docs") {
    group = "documentation"
    description = "Generate Dokka HTML documentation."
    dependsOn("dokkaHtml")
}

val placeholderDir = layout.buildDirectory.dir("generated/publication-placeholders")

val preparePublicationPlaceholders = tasks.register<Sync>("preparePublicationPlaceholders") {
    into(placeholderDir)
    from(layout.projectDirectory.file("PUBLIC_ARTIFACT_NOTICE.txt"))
}

val publicSourcesJar = tasks.register<Jar>("publicSourcesJar") {
    group = "build"
    description = "Build the placeholder sources archive required for public publishing."
    dependsOn(preparePublicationPlaceholders)
    archiveClassifier.set("sources")
    from(placeholderDir)
    include("PUBLIC_ARTIFACT_NOTICE.txt")
}

val publicJavadocJar = tasks.register<Jar>("publicJavadocJar") {
    group = "build"
    description = "Build the placeholder javadoc archive required for public publishing."
    dependsOn(preparePublicationPlaceholders)
    archiveClassifier.set("javadoc")
    from(placeholderDir)
    include("PUBLIC_ARTIFACT_NOTICE.txt")
}

publishing {
    repositories {
        maven {
            name = "central"
            val releaseUrl = propertyOrEnv(
                "publicMavenReleaseUrl",
                "PUBLIC_MAVEN_RELEASE_URL"
            ) ?: "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotUrl = propertyOrEnv(
                "publicMavenSnapshotUrl",
                "PUBLIC_MAVEN_SNAPSHOT_URL"
            ) ?: "https://central.sonatype.com/repository/maven-snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotUrl else releaseUrl)
            credentials {
                username = propertyOrEnv("centralPortalUsername", "CENTRAL_PORTAL_USERNAME")
                    ?: propertyOrEnv("ossrhUsername", "OSSRH_USERNAME")
                password = propertyOrEnv("centralPortalPassword", "CENTRAL_PORTAL_PASSWORD")
                    ?: propertyOrEnv("ossrhPassword", "OSSRH_PASSWORD")
            }
        }

        val githubRepository = propertyOrEnv("githubPackagesRepository", "GITHUB_REPOSITORY")
        if (!githubRepository.isNullOrBlank()) {
            maven {
                name = "github"
                url = uri("https://maven.pkg.github.com/$githubRepository")
                credentials {
                    username = propertyOrEnv("githubPackagesUsername", "GITHUB_ACTOR")
                    password = propertyOrEnv("githubPackagesToken", "GITHUB_TOKEN")
                }
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(publicSourcesJar)
            artifact(publicJavadocJar)
            pom {
                name.set((findProperty("pomName") as String?) ?: project.name)
                description.set((findProperty("pomDescription") as String?) ?: project.name)
                url.set((findProperty("pomUrl") as String?) ?: "https://github.com/ainsoft/rag")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("ainsoft")
                        name.set("Ainsoft")
                        email.set("dev@ainsoft.com")
                    }
                }
                scm {
                    url.set((findProperty("pomScmUrl") as String?) ?: "https://github.com/ainsoft/rag")
                    connection.set(
                        (findProperty("pomScmConnection") as String?)
                            ?: "scm:git:https://github.com/ainsoft/rag.git"
                    )
                    developerConnection.set(
                        (findProperty("pomScmDeveloperConnection") as String?)
                            ?: "scm:git:ssh://git@github.com/ainsoft/rag.git"
                    )
                }
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (!signingKey.isNullOrBlank() && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}

tasks.register("publishPublicModule") {
    group = "publishing"
    description = "Publish the autoconfigure module to the configured Maven Central repository."
    dependsOn("publish")
}

tasks.register("publishSnapshotToGitHubPackages") {
    group = "publishing"
    description = "Publish the snapshot build to GitHub Packages."
    val githubRepository = propertyOrEnv("githubPackagesRepository", "GITHUB_REPOSITORY")
    if (!githubRepository.isNullOrBlank()) {
        dependsOn("publishMavenPublicationToGithubRepository")
    }
    doFirst {
        check(!githubRepository.isNullOrBlank()) {
            "githubPackagesRepository or GITHUB_REPOSITORY must be set to publish snapshots to GitHub Packages"
        }
    }
}

tasks.register("uploadPublicReleaseToCentralPortal") {
    group = "publishing"
    description = "Upload the current release staging repository to the Maven Central Portal."
    onlyIf { !version.toString().endsWith("SNAPSHOT") }
    doLast {
        val namespace = propertyOrEnv("centralNamespace", "CENTRAL_NAMESPACE")
            ?: error("centralNamespace or CENTRAL_NAMESPACE is required for Central Portal uploads")
        val username = propertyOrEnv("centralPortalUsername", "CENTRAL_PORTAL_USERNAME")
            ?: propertyOrEnv("ossrhUsername", "OSSRH_USERNAME")
            ?: error("centralPortalUsername or CENTRAL_PORTAL_USERNAME is required")
        val password = propertyOrEnv("centralPortalPassword", "CENTRAL_PORTAL_PASSWORD")
            ?: propertyOrEnv("ossrhPassword", "OSSRH_PASSWORD")
            ?: error("centralPortalPassword or CENTRAL_PORTAL_PASSWORD is required")
        val publishingType = propertyOrEnv("centralPublishingType", "CENTRAL_PUBLISHING_TYPE")
            ?: "automatic"
        val encodedToken = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        val requestUrl = URI(
            "https",
            "ossrh-staging-api.central.sonatype.com",
            "/manual/upload/defaultRepository/$namespace",
            "publishing_type=$publishingType",
            null
        ).toURL()
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $encodedToken")
            setRequestProperty("Content-Length", "0")
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?: "no response body"
            error("Central Portal upload failed with HTTP $responseCode: $errorBody")
        }
    }
}

tasks.register("publishPublicRelease") {
    group = "publishing"
    description = "Publish the release build and transfer it to Maven Central Portal."
    dependsOn("publishPublicModule")
    if (!version.toString().endsWith("SNAPSHOT")) {
        finalizedBy("uploadPublicReleaseToCentralPortal")
    }
}

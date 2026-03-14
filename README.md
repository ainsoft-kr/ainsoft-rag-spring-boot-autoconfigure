# Ainsoft RAG Spring Boot Autoconfigure

`ainsoft-rag-spring-boot-autoconfigure`는 Ainsoft RAG 엔진 모듈을 Spring Boot 빈으로 연결하는 자동설정 모듈입니다.

보통 직접 추가하기보다 `ainsoft-rag-spring-boot-starter`를 사용하는 편이 적절합니다.

## Maven Coordinate

```kotlin
implementation("com.ainsoft.rag:ainsoft-rag-spring-boot-autoconfigure:0.1.0")
```

## What It Provides

- `RagAutoConfiguration`
- `RagProperties`
- 기본 `EmbeddingProvider`, `Chunker`, `RagEngine` 빈 구성
- stats cache/file cache 빈 구성
- provider health auto export lifecycle 구성
- servlet 웹 애플리케이션용 `RagAdminAutoConfiguration`
- 공통 관리자 UI `/rag-admin`
- 공통 운영 API `/api/rag/admin`

## Admin UI

관리자 UI는 servlet 기반 Spring Boot 웹 애플리케이션에서 자동 활성화됩니다.

```yaml
rag:
  admin:
    enabled: true
    basePath: /rag-admin
    apiBasePath: /api/rag/admin
    defaultRecentProviderWindowMillis: 60000
```

비활성화:

```yaml
rag:
  admin:
    enabled: false
```

## Build

```bash
./gradlew build
```

## Docs

Kotlin API 문서는 Dokka로 생성합니다.

```bash
./gradlew docs
```

생성 결과는 `build/dokka/html/index.html`에 있습니다.

로컬에서 엔진을 배포 없이 소비하려면 먼저 [ainsoft-rag-engine](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-engine) 을 `mavenLocal()`에 publish 해야 합니다.

```bash
cd ../ainsoft-rag-engine
./gradlew publishPublicModulesToMavenLocal

cd ../ainsoft-rag-spring-boot-autoconfigure
./gradlew build
```

## Publishing

snapshot:

```bash
./gradlew publishPublicModule
```

release:

```bash
./gradlew publishPublicRelease
```

`sources.jar`와 `javadoc.jar`는 Maven Central 요구사항 충족용 placeholder archive로 배포됩니다.

## Local Maven Flow

엔진 snapshot을 로컬 Maven 저장소에 올린 뒤 이 프로젝트를 publish 하면, starter가 같은 좌표를 다시 소비할 수 있습니다.

```bash
cd ../ainsoft-rag-engine
./gradlew publishPublicModulesToMavenLocal

cd ../ainsoft-rag-spring-boot-autoconfigure
./gradlew publishToMavenLocal
```

필요한 로컬 설정 예시는 `gradle.properties.template`에 있습니다.

GitHub Actions secrets:

- `MAVEN_CENTRAL_NAMESPACE`
- `MAVEN_PORTAL_USERNAME`
- `MAVEN_PORTAL_PASSWORD`
- `MAVEN_CENTRAL_PUBLISHING_TYPE`
- `MAVEN_SIGNING_KEY`
- `MAVEN_SIGNING_PASSWORD`

로컬 `~/.gradle/gradle.properties` 또는 CI secret으로 들어가야 하는 핵심 키:

- `centralPortalUsername`
- `centralPortalPassword`
- `centralPublishingType`
- `signingKey`
- `signingPassword`

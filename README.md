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

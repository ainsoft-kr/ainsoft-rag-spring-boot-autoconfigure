# Ainsoft RAG Spring Boot Autoconfigure

`ainsoft-rag-spring-boot-autoconfigure`는 Ainsoft RAG 엔진 모듈을 Spring Boot 빈으로 연결하는 자동설정 모듈입니다.

보통 직접 추가하기보다 `ainsoft-rag-spring-boot-starter`를 사용하는 편이 적절합니다.

이 모듈의 역할은 단순히 `RagEngine` 빈 하나를 만드는 것에 그치지 않습니다. 실제로는 아래를 함께 담당합니다.

- `rag.*` 설정을 엔진 옵션으로 변환
- `llm.*` 공통 provider 설정을 해석해 answer/query rewrite/summarizer에 연결
- chunker, embedding provider, reranker, summarizer, stats cache store 빈 구성
- provider health auto export lifecycle 등록
- servlet 기반 운영 UI `/rag-admin` 및 운영 API `/api/rag/admin` 자동 활성화
- 관리자 기능별 접근 통제와 feature-role 매핑 제공

## Maven Coordinate

```kotlin
implementation("com.ainsoft.rag:ainsoft-rag-spring-boot-autoconfigure:0.1.0")
```

## What It Provides

- `RagAutoConfiguration`
- `RagProperties`
- `LlmProperties`
- 기본 `EmbeddingProvider`, `Chunker`, `RagEngine` 빈 구성
- stats cache/file cache 빈 구성
- provider health auto export lifecycle 구성
- servlet 웹 애플리케이션용 `RagAdminAutoConfiguration`
- 공통 관리자 UI `/rag-admin`
- 공통 운영 API `/api/rag/admin`

## Configuration Surface

핵심 설정은 `RagProperties`와 `RagAdminProperties` 두 축으로 나뉩니다.

### Engine Configuration

대표적인 `rag.*` 키는 아래와 같습니다.

- `rag.indexPath`
- `rag.embeddingProvider`
- `rag.chunkerType`
- `rag.contextualRetrievalEnabled`
- `rag.rerankerEnabled`
- `rag.rerankerType`
- `rag.correctiveRetrievalEnabled`
- `rag.queryRewriteEnabled`
- `rag.hierarchicalSummariesEnabled`
- `rag.statsCacheStoreType`
- `rag.providerHealthAutoExportPath`
- `rag.providerHealthAutoExportPushUrl`

기본값은 heuristic 중심으로 잡혀 있어 외부 provider 없이도 시작 가능하고, 필요해지면 provider 기반 재작성/재랭킹/요약으로 확장할 수 있습니다.

`llm.*`를 추가하면 provider를 이름으로 묶어서 재사용할 수 있습니다.

예시:

```yaml
llm:
  defaultProvider: openai
  providers:
    openai:
      kind: openai-compatible
      baseUrl: https://api.openai.com/v1
      apiKey: ${OPENAI_API_KEY}
      model: gpt-4o-mini
    gemini:
      kind: gemini
      baseUrl: https://generativelanguage.googleapis.com/v1beta/models
      apiKey: ${GEMINI_API_KEY}
      model: gemini-2.0-flash
    anthropic:
      kind: anthropic
      baseUrl: https://api.anthropic.com/v1
      apiKey: ${ANTHROPIC_API_KEY}
      model: claude-3-5-sonnet-latest
  queryRewrite:
    provider: openai
    model: gpt-4o-mini
  summarizer:
    provider: openai
    model: gpt-4o-mini
```

지원하는 provider kind는 `openai-compatible`, `openai`, `anthropic`, `claude`, `gemini`, `google-gemini`, `vertex`, `vertex-ai`, `vertex-gemini`입니다.
`gemini`는 Google Generative Language API를, `vertex*`는 Vertex AI Gemini 엔드포인트를 대상으로 둡니다.

예시:

```yaml
rag:
  indexPath: ./rag-index
  embeddingProvider: hash
  chunkerType: sliding
  slidingWindowSize: 240
  slidingOverlap: 40
  contextualRetrievalEnabled: true
  rerankerEnabled: true
  rerankerType: heuristic
  correctiveRetrievalEnabled: true
  queryRewriteEnabled: true
  hierarchicalSummariesEnabled: true
  statsCacheStoreType: file
  statsCacheFilePath: ./rag-index/stats-cache.json
```

### Provider Health Export

provider health export는 운영형 기능입니다.

- 파일 export
- remote push
- retry/backoff
- dead-letter
- async queue
- HMAC signature header

예시:

```yaml
rag:
  providerHealthAutoExportIntervalMillis: 10000
  providerHealthAutoExportWindowMillis: 60000
  providerHealthAutoExportPushUrl: https://ops.example.com/rag/provider-health
  providerHealthAutoExportPushFormat: json
  providerHealthAutoExportPushMaxRetries: 2
  providerHealthAutoExportPushRetryBackoffMillis: 250
  providerHealthAutoExportPushAsyncEnabled: true
  providerHealthAutoExportPushQueueCapacity: 64
  providerHealthAutoExportPushDeadLetterPath: ./ops/provider-health-dead-letter.json
```

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

관리자 UI 정적 자산은 이 저장소의 SvelteKit frontend에서 생성됩니다.

## Admin Feature Model

기본 관리자 화면은 아래 기능군으로 구성됩니다.

- overview
- search
- text-ingest
- file-ingest
- documents
- tenants
- provider-history
- search-audit
- job-history
- config
- bulk-operations
- access-security

기본 역할 매핑도 함께 제공됩니다.

- `ADMIN`
- `OPS`
- `AUDITOR`

예를 들어 `access-security`는 기본적으로 `ADMIN`만 접근 가능하고, `overview`, `provider-history`, `search-audit`, `job-history`, `config`는 `AUDITOR`도 접근할 수 있습니다.

예시:

```yaml
rag:
  admin:
    security:
      enabled: true
      tokenHeaderName: X-Rag-Admin-Token
      tokenQueryParameter: access_token
      tokens:
        admin-token: ADMIN
        ops-token: OPS
        audit-token: AUDITOR
      featureRoles:
        overview: [ADMIN, OPS, AUDITOR]
        search: [ADMIN, OPS, AUDITOR]
        text-ingest: [ADMIN, OPS]
        file-ingest: [ADMIN, OPS]
        documents: [ADMIN, OPS, AUDITOR]
        tenants: [ADMIN, OPS]
        provider-history: [ADMIN, OPS, AUDITOR]
        search-audit: [ADMIN, OPS, AUDITOR]
        job-history: [ADMIN, OPS, AUDITOR]
        config: [ADMIN, OPS, AUDITOR]
        bulk-operations: [ADMIN, OPS]
        access-security: [ADMIN]
```

## Operational API Surface

자동설정이 등록하는 운영 API는 단순 stats 조회를 넘어서 실제 관리 작업을 수행하도록 설계돼 있습니다.

- ingest / file upload
- search / diagnose-search
- stats / provider-health
- documents list / detail / delete / reindex / source-preview
- tenants list / detail / delete
- operations snapshot / restore / optimize / rebuild-metadata
- provider-history / search-audit / job-history
- access-security / config

즉, 이 모듈은 "엔진을 Spring에 연결하는 glue code"이면서 동시에 운영 관리면을 노출하는 adapter layer입니다.

## Frontend

프론트엔드 소스는 [frontend](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-autoconfigure/frontend) 아래에 있습니다.

정적 산출물을 빌드만 하려면:

```bash
./gradlew buildFrontend
```

빌드 결과를 실제 Spring 리소스 경로인
`src/main/resources/META-INF/resources/rag-admin` 에 동기화하려면:

```bash
./gradlew deployFrontend
```

일반 `build`는 frontend를 함께 빌드하고 jar 안의
`META-INF/resources/rag-admin` 로 패키징합니다.

이미 동기화된 리소스를 그대로 사용하고 frontend 재빌드를 건너뛰려면:

```bash
./gradlew build -PskipFrontendBuild=true
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

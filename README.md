<a id="readme-top"></a>

<div align="center">

# spring-ai-openclaw

**Spring AI model integration for OpenClaw**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.partmeai/spring-ai-openclaw)](https://github.com/partme-ai/spring-ai-openclaw)
[![Java](https://img.shields.io/badge/Java-17-orange)](#3-requirements-and-compatibility)
[![License](https://img.shields.io/badge/license-Apache-2.0-green)](https://www.apache.org/licenses/LICENSE-2.0)

[简体中文](./README.zh-CN.md) | [English](./README.md)

[Positioning](#1-positioning) · [Capabilities](#2-core-capabilities) ·
[Dependency](#5-dependency) · [Quick Start](#6-quick-start) ·
[Configuration](#7-configuration-reference) · [Versions](#9-version-lines-and-compatibility) ·
[Build](#10-build-and-test) · [License](#12-license)

</div>

---

> **Current Version**：`2.0.x.20260630-SNAPSHOT`<br>
> **JDK Baseline**：`17`<br>
> **Group ID**：`io.github.partmeai`<br>
> **Artifact ID**：`spring-ai-openclaw`<br>
> **License**：Apache License 2.0<br>

## 1. Positioning

**spring-ai-openclaw** adapts the OpenClaw Gateway HTTP APIs to Spring AI chat, embedding, and response model contracts. Clients are created explicitly through builders so applications retain ownership of credentials, HTTP configuration, concurrency limits, and lifecycle.

| Dimension | Description |
|---|---|
| Type | Spring AI model integration module |
| Consumers | Spring AI applications using an OpenClaw Gateway |
| Core Capabilities | ChatModel, EmbeddingModel, Responses API, SSE and tool calling |
| JDK | `17` |
| Coordinates | `io.github.partmeai:spring-ai-openclaw:2.0.x.20260630-SNAPSHOT` |

## 2. Core Capabilities

| Capability | Status | Description |
|---|:---:|---|
| Chat model | ✅ Stable | OpenClaw chat completion and streaming adapter |
| Embedding model | ✅ Stable | OpenClaw embedding adapter |
| Responses model | ✅ Stable | OpenClaw Responses API adapter |
| Concurrency control | ✅ Stable | Bounded, non-blocking request admission |

## 3. Requirements and Compatibility

| Dependency | Minimum | Evidence |
|---|---:|---|
| JDK | `17` | `pom.xml` |
| Spring AI | `2.0.0` | `spring-ai-bom` |
| Spring Framework | `7.0.8` | `spring-framework-bom` |
| Maven | `3.6+` | Maven Enforcer |

## 4. Main Components

| Component | Responsibility |
|---|---|
| `OpenClawApi` | Chat, embedding, model discovery, and SSE transport |
| `OpenClawResponsesApi` | OpenAI-compatible Responses API transport |
| `OpenClawChatModel` | Spring AI `ChatModel` and `StreamingChatModel` adapter |
| `OpenClawEmbeddingModel` | Spring AI embedding adapter |
| `OpenClawResponsesModel` | Spring AI Responses model adapter |

## 5. Dependency

```xml
<dependency>
    <groupId>io.github.partmeai</groupId>
    <artifactId>spring-ai-openclaw</artifactId>
    <version>2.0.x.20260630-SNAPSHOT</version>
</dependency>
```

No additional easy4j component dependencies.

## 6. Quick Start

### 6.1 Add dependency

Add the dependency above to your `pom.xml`.

### 6.2 Configure

```java
OpenClawApi api = OpenClawApi.builder().build();
OpenClawChatModel chatModel = OpenClawChatModel.builder()
    .openclawApi(api)
    .build();
```

### 6.3 Use the model

```java
ChatResponse response = chatModel.call(new Prompt("Hello OpenClaw"));
```

## 7. Builder Configuration

The module does not bind a Spring Boot property prefix. Configure the Gateway URL,
HTTP builders, error handlers, and concurrency limit through `OpenClawApi.Builder`;
configure model defaults and tool calling through the model builders.

## 8. Version Lines and Compatibility

| Branch | JDK | Platform | Component Version | Status |
|---|---:|---:|---|:---:|
| `feature/1.0.x` | `17` | Spring AI 1.1.7 / Framework 6.2.x | `1.0.x.20260630-SNAPSHOT` | Maintenance |
| `feature/2.0.x` | `17` | Spring AI 2.0.0 / Framework 7.0.x | `2.0.x.20260630-SNAPSHOT` | Current |

## 9. Build and Test

```bash
mvn clean verify
mvn -pl spring-ai-openclaw -am test
```

## 10. Troubleshooting

| Symptom | Diagnosis | Resolution |
|---|---|---|
| Bean not created | Check auto-configuration report | Verify `spring.ai.openclaw.enabled=true` and classpath |
| `ClassNotFoundException` | Missing dependency | Add the required module |
| Version conflict | `mvn dependency:tree` | Use BOM for version alignment |

## 11. Contribution

1. Fork the repository.
2. Create a feature branch.
3. Run `mvn clean verify` before submitting.
4. Submit a pull request.

## 12. License

This project is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

---

<div align="center">

[Back to top](#readme-top) · [Issues](https://github.com/easy-4-java/spring-ai-openclaw/issues) · [Repository](https://github.com/easy-4-java/spring-ai-openclaw)

</div>

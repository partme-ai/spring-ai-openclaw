<a id="readme-top"></a>

<div align="center">

# spring-ai-openclaw

**OpenClaw 的 Spring AI 模型适配模块**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.partmeai/spring-ai-openclaw)](https://github.com/partme-ai/spring-ai-openclaw)
[![Java](https://img.shields.io/badge/Java-17-orange)](#3-运行要求与兼容性)
[![License](https://img.shields.io/badge/license-Apache-2.0-green)](https://www.apache.org/licenses/LICENSE-2.0)

[English](./README.md) | [简体中文](./README.zh-CN.md)

[项目定位](#1-项目定位) · [核心能力](#2-核心能力) ·
[引入依赖](#5-引入依赖) · [快速开始](#6-快速开始) ·
[配置参考](#7-配置参考) · [版本线](#8-版本线与兼容性) ·
[构建测试](#9-构建与测试) · [许可证](#12-许可证)

</div>

---

> **当前版本**：`2.0.x.20260630-SNAPSHOT`<br>
> **JDK 基线**：`17`<br>
> **Group ID**：`io.github.partmeai`<br>
> **Artifact ID**：`spring-ai-openclaw`<br>
> **许可证**：Apache License 2.0<br>

## 1. 项目定位

**spring-ai-openclaw** 将 OpenClaw Gateway HTTP API 适配为 Spring AI 的聊天、嵌入和 Responses 模型契约。客户端通过 Builder 显式创建，由应用负责认证、HTTP 配置、并发上限和生命周期。

| 维度 | 说明 |
|---|---|
| 类型 | Spring AI 模型适配模块 |
| 消费方 | 使用 OpenClaw Gateway 的 Spring AI 应用 |
| 核心能力 | ChatModel、EmbeddingModel、Responses API、SSE 与工具调用 |
| JDK | `17` |
| 坐标 | `io.github.partmeai:spring-ai-openclaw:2.0.x.20260630-SNAPSHOT` |

## 2. 核心能力

| 能力 | 状态 | 说明 |
|---|:---:|---|
| 聊天模型 | ✅ 稳定 | OpenClaw 聊天补全与流式适配 |
| 嵌入模型 | ✅ 稳定 | OpenClaw 嵌入适配 |
| Responses 模型 | ✅ 稳定 | OpenClaw Responses API 适配 |
| 并发控制 | ✅ 稳定 | 有界、非阻塞的请求准入 |

## 3. 运行要求与兼容性

| 依赖 | 最低版本 | 证据来源 |
|---|---:|---|
| JDK | `17` | `pom.xml` |
| Spring AI | `2.0.0` | `spring-ai-bom` |
| Spring Framework | `7.0.8` | `spring-framework-bom` |
| Maven | `3.6+` | Maven Enforcer |

## 4. 主要组件

| 组件 | 职责 |
|---|---|
| `OpenClawApi` | 聊天、嵌入、模型发现与 SSE 传输 |
| `OpenClawResponsesApi` | OpenAI 兼容 Responses API 传输 |
| `OpenClawChatModel` | Spring AI `ChatModel` 与 `StreamingChatModel` 适配 |
| `OpenClawEmbeddingModel` | Spring AI 嵌入适配 |
| `OpenClawResponsesModel` | Spring AI Responses 模型适配 |

## 5. 引入依赖

```xml
<dependency>
    <groupId>io.github.partmeai</groupId>
    <artifactId>spring-ai-openclaw</artifactId>
    <version>2.0.x.20260630-SNAPSHOT</version>
</dependency>
```

无其他 easy4j 组件依赖。

## 6. 快速开始

### 6.1 引入依赖

在 `pom.xml` 中添加上述依赖。

### 6.2 配置

```java
OpenClawApi api = OpenClawApi.builder().build();
OpenClawChatModel chatModel = OpenClawChatModel.builder()
    .openclawApi(api)
    .build();
```

### 6.3 使用模型

```java
ChatResponse response = chatModel.call(new Prompt("Hello OpenClaw"));
```

## 7. Builder 配置

本模块不绑定 Spring Boot 配置前缀。Gateway 地址、HTTP Builder、错误处理器和并发上限
通过 `OpenClawApi.Builder` 配置；模型默认选项和工具调用通过各模型 Builder 配置。

## 8. 版本线与兼容性

| 分支 | JDK | 平台 | 组件版本 | 状态 |
|---|---:|---:|---|:---:|
| `feature/1.0.x` | `17` | Spring AI 1.1.7 / Framework 6.2.x | `1.0.x.20260630-SNAPSHOT` | 维护中 |
| `feature/2.0.x` | `17` | Spring AI 2.0.0 / Framework 7.0.x | `2.0.x.20260630-SNAPSHOT` | 当前分支 |

## 9. 构建与测试

```bash
mvn clean verify
mvn -pl spring-ai-openclaw -am test
```

## 10. 排障

| 症状 | 诊断 | 解决 |
|---|---|---|
| Bean 未创建 | 查看自动装配报告 | 确认 `spring.ai.openclaw.enabled=true` 与 classpath |
| `ClassNotFoundException` | 缺少依赖 | 引入对应模块 |
| 版本冲突 | `mvn dependency:tree` | 使用 BOM 统一版本 |

## 11. 贡献

1. Fork 本仓库。
2. 创建特性分支。
3. 提交前运行 `mvn clean verify`。
4. 提交 Pull Request。

## 12. 许可证

本项目采用 [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可证。

---

<div align="center">

[返回顶部](#readme-top) · [问题反馈](https://github.com/easy-4-java/spring-ai-openclaw/issues) · [仓库地址](https://github.com/easy-4-java/spring-ai-openclaw)

</div>

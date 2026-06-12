# spring-ai-openclaw

Spring AI 模型集成：将 OpenClaw Gateway 桥接到 Spring AI 的 `ChatModel` 和 `EmbeddingModel` 接口。

通过 OpenClaw Gateway 的 OpenAI 兼容端点实现：

- `POST /v1/chat/completions` → `ChatModel`（流式 + 非流式）
- `POST /v1/embeddings` → `EmbeddingModel`
- `GET /v1/models` → 模型列表

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.hiwepy</groupId>
    <artifactId>spring-ai-openclaw</artifactId>
    <version>2.7.x.20260527-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
spring:
  ai:
    openclaw:
      base-url: http://localhost:18789
      gateway-auth-token: your-gateway-token
      model: openclaw/default
```

### 3. 注入使用

```java
@RestController
public class ChatController {

    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatModel.call(new Prompt(message))
            .getResult().getOutput().getText();
    }

    @GetMapping("/chat/stream")
    public Flux<String> chatStream(@RequestParam String message) {
        return chatModel.stream(new Prompt(message))
            .map(response -> response.getResult().getOutput().getText());
    }
}
```

## OpenClaw 特有功能

### Agent 目标路由

OpenClaw 将 `model` 字段解释为 agent 目标，而非原始模型 ID：

```java
// 使用默认 agent
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/default")
    .build();

// 使用指定 agent
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/research")
    .build();
```

### 覆盖后端模型

通过 `x-openclaw-model` 头覆盖 agent 使用的后端模型：

```java
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/default")
    .openclawModel("openai/gpt-5.4")  // 覆盖后端模型
    .build();
```

### 会话路由

```java
// 通过 user 字段派生稳定 session key
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/default")
    .user("conv:my-conversation-id")
    .build();

// 通过 x-openclaw-session-key 显式控制
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/default")
    .openclawSessionKey("my-session-key")
    .build();
```

### 通道上下文

```java
OpenClawChatOptions options = OpenClawChatOptions.builder()
    .model("openclaw/default")
    .openclawMessageChannel("slack")
    .build();
```

## 配置属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `spring.ai.openclaw.base-url` | `http://localhost:18789` | Gateway HTTP 根地址 |
| `spring.ai.openclaw.gateway-auth-token` | | 控制面认证令牌 (`gateway.auth.token`) |
| `spring.ai.openclaw.gateway-auth-password` | | 控制面密码 (`gateway.auth.password`) |
| `spring.ai.openclaw.hooks-token` | | Webhook 令牌 (`hooks.token`) |
| `spring.ai.openclaw.model` | `openclaw/default` | 默认 agent 目标模型 |
| `spring.ai.openclaw.connect-timeout-millis` | `15000` | 连接超时 |
| `spring.ai.openclaw.read-timeout-millis` | `120000` | 读取超时 |
| `spring.ai.openclaw.verify-ssl` | `true` | 是否校验 HTTPS 证书 |

## 相关文档

- [OpenAI Chat Completions](https://docs.openclaw.ai/gateway/openai-http-api)
- [OpenResponses API](https://docs.openclaw.ai/gateway/openresponses-http-api)
- [Gateway Protocol](https://docs.openclaw.ai/gateway/protocol)
- [CLI Backends](https://docs.openclaw.ai/gateway/cli-backends)
- [CLI Reference](https://docs.openclaw.ai/cli)

## 依赖关系

- Spring Boot 3.4+
- Spring AI 1.0.0+
- JDK 17+

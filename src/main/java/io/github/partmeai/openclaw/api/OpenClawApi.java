/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.partmeai.openclaw.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.partmeai.openclaw.api.common.OpenClawApiConstants;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * <p>OpenClaw Gateway 的 OpenAI 兼容 HTTP 客户端。</p>
 *
 * <p>封装 {@code /v1/chat/completions}、{@code /v1/responses}、{@code /v1/models}
 * 与 {@code /v1/embeddings} 端点，并同时提供同步、异步和 SSE 流式访问方式。模型字段采用
 * {@code openclaw/default} 或 {@code openclaw/&lt;agentId&gt;} 形式的智能体目标路由，
 * {@code x-openclaw-*} 请求头用于覆盖后端模型、会话路由和消息通道等上下文。</p>
 *
 * <p>同一客户端的全部端点共享 {@link OpenClawRequestLimiter} 并发门禁。聊天 SSE 流在
 * 订阅时计入活动流，遇到 {@code [DONE]} 后停止，并通过
 * {@link OpenClawStreamToolCallAggregator} 合并被拆分的工具调用参数。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
@Slf4j
public final class OpenClawApi {

	/** <p>客户端默认允许的最大并发请求数。</p> */
	public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 512;

	/** <p>OpenAI 兼容 SSE 流结束标记。</p> */
	private static final String SSE_DONE = "[DONE]";

	/**
	 * <p>创建 OpenClaw API 客户端构建器。</p>
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** <p>请求体为空时使用的校验错误消息。</p> */
	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	/** <p>执行同步 JSON 请求的 REST 客户端。</p> */
	private final RestClient restClient;

	/** <p>执行异步请求和 SSE 请求的响应式客户端。</p> */
	private final WebClient webClient;

	/** <p>处理聊天 SSE 数据解析错误的策略。</p> */
	private final SseErrorHandler sseErrorHandler;

	/** <p>当前仍处于订阅生命周期内的聊天 SSE 流数量。</p> */
	private final AtomicInteger activeStreams = new AtomicInteger();

	/** <p>所有端点共享的请求并发门禁。</p> */
	private final OpenClawRequestLimiter requestLimiter;

	/** <p>合并聊天 SSE 工具调用增量片段的聚合器。</p> */
	private final OpenClawStreamToolCallAggregator streamToolCallAggregator =
			new OpenClawStreamToolCallAggregator();

	private OpenClawApi(String baseUrl, RestClient.Builder restClientBuilder,
			WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler,
			SseErrorHandler sseErrorHandler, int maxConcurrentRequests) {
		this.restClient = restClientBuilder
				.clone()
				.baseUrl(baseUrl)
				.defaultHeaders(headers -> {
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setAccept(List.of(MediaType.APPLICATION_JSON));
				})
				.defaultStatusHandler(responseErrorHandler)
				.build();

		this.webClient = webClientBuilder
				.clone()
				.baseUrl(baseUrl)
				.defaultHeaders(headers -> {
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
				})
				.build();

		this.sseErrorHandler = sseErrorHandler;
		this.requestLimiter = new OpenClawRequestLimiter(maxConcurrentRequests);
		log.debug("Initialized OpenClaw API client: baseUrl={}, maxConcurrentRequests={}",
				baseUrl, maxConcurrentRequests);
	}

	// --------------------------------------------------------------------------
	// Chat Completions
	// --------------------------------------------------------------------------

	/**
	 * <p>同步创建聊天补全。</p>
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 非流式聊天请求
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 聊天响应
	 */
	public ChatResponse chat(ChatRequest chatRequest) {
		return chat(chatRequest, Map.of());
	}

	/**
	 * <p>携带额外请求头同步创建聊天补全。</p>
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 非流式聊天请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加到 HTTP 请求的请求头
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 聊天响应
	 * @throws java.lang.IllegalArgumentException 当请求为空或开启流式模式时抛出
	 */
	public ChatResponse chat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");

		var requestSpec = this.restClient.post().uri("/v1/chat/completions");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.execute(() -> requestSpec.body(chatRequest).retrieve().body(ChatResponse.class));
	}

	/**
	 * <p>异步创建聊天补全。</p>
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 非流式聊天请求
	 * @return reactor.core.publisher.Mono&lt;ChatResponse&gt; 发出聊天响应的 Publisher
	 */
	public Mono<ChatResponse> chatAsync(ChatRequest chatRequest) {
		return chatAsync(chatRequest, Map.of());
	}

	/**
	 * <p>携带额外请求头异步创建聊天补全。</p>
	 *
	 * <p>并发槽位在订阅时获取，并在成功、失败或取消时释放。</p>
	 *
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 非流式聊天请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加到 HTTP 请求的请求头
	 * @return reactor.core.publisher.Mono&lt;ChatResponse&gt; 受并发门禁保护的响应 Publisher
	 */
	public Mono<ChatResponse> chatAsync(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");
		var requestSpec = this.webClient.post().uri("/v1/chat/completions");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.guard(requestSpec.bodyValue(chatRequest).retrieve().bodyToMono(ChatResponse.class));
	}

	/**
	 * <p>以 SSE 方式创建流式聊天补全。</p>
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 已开启流式模式的聊天请求
	 * @return reactor.core.publisher.Flux&lt;ChatResponse&gt; 工具调用片段已聚合的聊天响应流
	 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#streaming-sse">OpenClaw Streaming SSE</a>
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest) {
		return streamingChat(chatRequest, Map.of());
	}

	/**
	 * <p>携带额外请求头创建 SSE 聊天补全。</p>
	 *
	 * <p>流在订阅时占用请求槽位并增加活动流计数；{@code [DONE]} 只作为终止标记，
	 * 不进入 JSON 反序列化。终止信号到达后，无论完成、失败还是取消，活动流计数和并发槽位
	 * 均会释放。</p>
	 *
	 * @param chatRequest io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 已开启流式模式的聊天请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加到 HTTP 请求的请求头
	 * @return reactor.core.publisher.Flux&lt;ChatResponse&gt; 受并发门禁保护的聊天响应流
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(chatRequest.stream(), "Request must set the stream property to true.");

		var requestSpec = this.webClient.post()
				.uri("/v1/chat/completions")
				.accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(requestSpec::header);

		// defer 将活动流计数和网络请求推迟到每次订阅，避免创建 Flux 时提前改变状态。
		return this.requestLimiter.guard(Flux.defer(() -> {
			int active = this.activeStreams.incrementAndGet();
			if (log.isTraceEnabled()) {
				log.trace("Opened OpenClaw chat stream: activeStreams={}", active);
			}
			Flux<ChatResponse> chunks = requestSpec
				.bodyValue(chatRequest)
				.retrieve()
				.bodyToFlux(String.class)
				// 先识别结束哨兵，再反序列化真实 JSON 数据块。
				.takeUntil(SSE_DONE::equals)
				.filter(data -> !SSE_DONE.equals(data))
					.map(data -> ModelOptionsUtils.<ChatResponse>jsonToObject(data, ChatResponse.class))
					.onErrorResume(this.sseErrorHandler::handle);
			return this.streamToolCallAggregator.aggregate(chunks)
					.doOnNext(chunk -> {
						if (log.isTraceEnabled()) {
							log.trace("SSE chunk: {}", chunk);
						}
					})
					.filter(chunk -> chunk.choices() != null && !chunk.choices().isEmpty())
				.doFinally(signal -> {
					int remaining = this.activeStreams.decrementAndGet();
					if (log.isTraceEnabled()) {
						log.trace("Closed OpenClaw chat stream: signal={}, activeStreams={}",
								signal, remaining);
					}
				});
		}));
	}

	/**
	 * <p>获取当前活动的聊天 SSE 流数量。</p>
	 * @return int 尚未终止的活动流数量
	 */
	public int getActiveStreamCount() {
		return this.activeStreams.get();
	}

	/**
	 * <p>获取当前占用并发槽位的请求数。</p>
	 * @return int 当前进行中的请求数
	 */
	public int getInFlightRequestCount() {
		return this.requestLimiter.getInFlightRequestCount();
	}

	/**
	 * <p>获取客户端最大并发请求数。</p>
	 * @return int 最大并发请求数
	 */
	public int getMaxConcurrentRequests() {
		return this.requestLimiter.getMaxConcurrentRequests();
	}

	// --------------------------------------------------------------------------
	// Responses
	// --------------------------------------------------------------------------

	/**
	 * <p>以通用映射作为请求体同步调用 Responses 端点。</p>
	 * @param responsesRequest java.util.Map&lt;String, Object&gt; Responses 请求体
	 * @return java.util.Map&lt;String, Object&gt; 通用响应映射
	 */
	public Map<String, Object> responses(Map<String, Object> responsesRequest) {
		return responses(responsesRequest, Map.of());
	}

	/**
	 * <p>携带额外请求头同步调用 Responses 端点。</p>
	 * @param responsesRequest java.util.Map&lt;String, Object&gt; Responses 请求体
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return java.util.Map&lt;String, Object&gt; 通用响应映射
	 */
	public Map<String, Object> responses(Map<String, Object> responsesRequest, Map<String, String> extraHeaders) {
		Assert.notNull(responsesRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post().uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.execute(() -> requestSpec.body(responsesRequest).retrieve().body(Map.class));
	}

	/**
	 * <p>携带额外请求头异步调用 Responses 端点。</p>
	 * @param responsesRequest java.util.Map&lt;String, Object&gt; Responses 请求体
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return reactor.core.publisher.Mono&lt;Map&gt; 受并发门禁保护的通用响应 Publisher
	 */
	public Mono<Map> responsesAsync(Map<String, Object> responsesRequest, Map<String, String> extraHeaders) {
		Assert.notNull(responsesRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.webClient.post().uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.guard(requestSpec.bodyValue(responsesRequest).retrieve().bodyToMono(Map.class));
	}

	// --------------------------------------------------------------------------
	// Models
	// --------------------------------------------------------------------------

	/**
	 * <p>同步列出 OpenClaw Gateway 可用的智能体目标。</p>
	 *
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ListModelResponse 包含
	 * {@code openclaw}、{@code openclaw/default} 和 {@code openclaw/&lt;agentId&gt;} 等目标
	 */
	public ListModelResponse listModels() {
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri("/v1/models").retrieve().body(ListModelResponse.class));
	}

	/**
	 * <p>异步列出 OpenClaw Gateway 可用的智能体目标。</p>
	 * @return reactor.core.publisher.Mono&lt;ListModelResponse&gt; 受并发门禁保护的模型列表 Publisher
	 */
	public Mono<ListModelResponse> listModelsAsync() {
		return this.requestLimiter.guard(this.webClient.get().uri("/v1/models")
			.retrieve().bodyToMono(ListModelResponse.class));
	}

	/**
	 * <p>同步查询指定智能体目标的信息。</p>
	 * @param modelId java.lang.String 非空智能体目标标识
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ModelResponse 模型信息
	 */
	public ModelResponse getModel(String modelId) {
		Assert.hasText(modelId, "modelId must not be empty");
		return this.requestLimiter.execute(() -> this.restClient.get()
			.uri("/v1/models/{id}", modelId).retrieve().body(ModelResponse.class));
	}

	// --------------------------------------------------------------------------
	// Embeddings
	// --------------------------------------------------------------------------

	/**
	 * <p>同步创建嵌入向量。</p>
	 * @param embeddingsRequest io.github.partmeai.openclaw.api.OpenClawApi.EmbeddingsRequest 嵌入请求
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.EmbeddingsResponse 嵌入响应
	 */
	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest) {
		return embed(embeddingsRequest, Map.of());
	}

	/**
	 * <p>携带额外请求头同步创建嵌入向量。</p>
	 * @param embeddingsRequest io.github.partmeai.openclaw.api.OpenClawApi.EmbeddingsRequest 嵌入请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.EmbeddingsResponse 嵌入响应
	 */
	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest, Map<String, String> extraHeaders) {
		Assert.notNull(embeddingsRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post().uri("/v1/embeddings");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.execute(() -> requestSpec.body(embeddingsRequest)
			.retrieve().body(EmbeddingsResponse.class));
	}

	/**
	 * <p>携带额外请求头异步创建嵌入向量。</p>
	 * @param embeddingsRequest io.github.partmeai.openclaw.api.OpenClawApi.EmbeddingsRequest 嵌入请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return reactor.core.publisher.Mono&lt;EmbeddingsResponse&gt; 受并发门禁保护的嵌入响应 Publisher
	 */
	public Mono<EmbeddingsResponse> embedAsync(EmbeddingsRequest embeddingsRequest,
			Map<String, String> extraHeaders) {
		Assert.notNull(embeddingsRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.webClient.post().uri("/v1/embeddings");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.guard(requestSpec.bodyValue(embeddingsRequest)
			.retrieve().bodyToMono(EmbeddingsResponse.class));
	}

	// ========================================================================
	// Request / Response Models — Chat Completions
	// ========================================================================

	/**
	 * <p>OpenAI 兼容的聊天补全请求体。</p>
	 *
	 * @param model java.lang.String OpenClaw 智能体目标标识
	 * @param messages java.util.List&lt;Message&gt; 按会话顺序排列的消息
	 * @param stream java.lang.Boolean 是否启用 SSE 流式响应
	 * @param tools java.util.List&lt;Tool&gt; 可供模型调用的函数工具
	 * @param toolChoice java.lang.Object 工具选择策略
	 * @param maxCompletionTokens java.lang.Integer 首选的最大补全 token 数
	 * @param maxTokens java.lang.Integer 遗留最大 token 数
	 * @param temperature java.lang.Double 采样温度
	 * @param topP java.lang.Double 核采样概率
	 * @param frequencyPenalty java.lang.Double 频率惩罚
	 * @param presencePenalty java.lang.Double 存在惩罚
	 * @param seed java.lang.Integer 随机种子
	 * @param stop java.lang.Object 停止序列
	 * @param user java.lang.String 用于稳定会话路由的用户标识
	 * @param streamOptions io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.StreamOptions 流式选项
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatRequest(
			@JsonProperty("model") String model,
			@JsonProperty("messages") List<Message> messages,
			@JsonProperty("stream") Boolean stream,
			@JsonProperty("tools") List<Tool> tools,
			@JsonProperty("tool_choice") Object toolChoice,
			@JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
			@JsonProperty("max_tokens") Integer maxTokens,
			@JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP,
			@JsonProperty("frequency_penalty") Double frequencyPenalty,
			@JsonProperty("presence_penalty") Double presencePenalty,
			@JsonProperty("seed") Integer seed,
			@JsonProperty("stop") Object stop,
			@JsonProperty("user") String user,
			@JsonProperty("stream_options") StreamOptions streamOptions
	) {

		/**
		 * <p>使用必需的模型标识创建聊天请求构建器。</p>
		 * @param model java.lang.String OpenClaw 智能体目标标识
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 新构建器
		 */
		public static Builder builder(String model) {
			return new Builder(model);
		}

		/**
		 * <p>控制 SSE 返回内容的流式选项。</p>
		 * @param includeUsage java.lang.Boolean 是否要求流中包含 usage 信息
		 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#streaming-sse">Streaming SSE</a>
		 */
		@JsonInclude(Include.NON_NULL)
		public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

		/**
		 * <p>聊天请求中的函数工具定义。</p>
		 * @param type io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Tool.Type 工具类型
		 * @param function io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Tool.Function 函数定义
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Tool(
				@JsonProperty("type") Type type,
				@JsonProperty("function") Function function) {

			/**
			 * <p>以默认函数类型创建工具。</p>
			 * @param function io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Tool.Function 函数定义
			 */
			public Tool(Function function) {
				this(Type.FUNCTION, function);
			}

			/** <p>聊天请求支持的工具类型。</p> */
			public enum Type {
				/** <p>函数调用工具。</p> */
				@JsonProperty("function") FUNCTION
			}

			/**
			 * <p>函数工具的名称、说明和 JSON Schema 参数定义。</p>
			 * @param name java.lang.String 函数名称
			 * @param description java.lang.String 函数用途说明
			 * @param parameters java.util.Map&lt;String, Object&gt; 输入参数 JSON Schema
			 */
			public record Function(
					@JsonProperty("name") String name,
					@JsonProperty("description") String description,
					@JsonProperty("parameters") Map<String, Object> parameters) {
			}
		}

		/** <p>{@link ChatRequest} 构建器。</p> */
		public static final class Builder {

			/** <p>必需的智能体目标标识。</p> */
			private final String model;
			/** <p>会话消息，默认为空列表。</p> */
			private List<Message> messages = List.of();
			/** <p>是否启用流式响应。</p> */
			private boolean stream = false;
			/** <p>函数工具列表。</p> */
			private List<Tool> tools = List.of();
			/** <p>工具选择策略。</p> */
			private Object toolChoice;
			/** <p>首选最大补全 token 数。</p> */
			private Integer maxCompletionTokens;
			/** <p>遗留最大 token 数。</p> */
			private Integer maxTokens;
			/** <p>采样温度。</p> */
			private Double temperature;
			/** <p>核采样概率。</p> */
			private Double topP;
			/** <p>频率惩罚。</p> */
			private Double frequencyPenalty;
			/** <p>存在惩罚。</p> */
			private Double presencePenalty;
			/** <p>随机种子。</p> */
			private Integer seed;
			/** <p>停止序列。</p> */
			private Object stop;
			/** <p>用户标识。</p> */
			private String user;
			/** <p>流式选项。</p> */
			private StreamOptions streamOptions;

			/**
			 * <p>创建聊天请求构建器。</p>
			 * @param model java.lang.String 非空智能体目标标识
			 * @throws java.lang.IllegalArgumentException 当模型标识为 {@code null} 时抛出
			 */
			public Builder(String model) {
				Assert.notNull(model, "The model can not be null.");
				this.model = model;
			}

			/**
			 * <p>设置会话消息。</p>
			 * @param messages java.util.List&lt;Message&gt; 会话消息
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder messages(List<Message> messages) { this.messages = messages; return this; }
			/**
			 * <p>设置是否启用 SSE。</p>
			 * @param stream boolean 是否流式
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder stream(boolean stream) { this.stream = stream; return this; }
			/**
			 * <p>设置函数工具。</p>
			 * @param tools java.util.List&lt;Tool&gt; 工具列表
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder tools(List<Tool> tools) { this.tools = tools; return this; }
			/**
			 * <p>设置工具选择策略。</p>
			 * @param toolChoice java.lang.Object 工具选择策略
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder toolChoice(Object toolChoice) { this.toolChoice = toolChoice; return this; }

			/**
			 * <p>设置首选最大补全 token 数；与遗留字段同时设置时优先。</p>
			 * @param v java.lang.Integer 最大补全 token 数
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder maxCompletionTokens(Integer v) { this.maxCompletionTokens = v; return this; }
			/**
			 * <p>设置遗留最大 token 数。</p>
			 * @param v java.lang.Integer 最大 token 数
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }
			/**
			 * <p>设置采样温度。</p>
			 * @param v java.lang.Double 采样温度
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder temperature(Double v) { this.temperature = v; return this; }
			/**
			 * <p>设置核采样概率。</p>
			 * @param v java.lang.Double 核采样概率
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder topP(Double v) { this.topP = v; return this; }
			/**
			 * <p>设置频率惩罚。</p>
			 * @param v java.lang.Double 频率惩罚
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder frequencyPenalty(Double v) { this.frequencyPenalty = v; return this; }
			/**
			 * <p>设置存在惩罚。</p>
			 * @param v java.lang.Double 存在惩罚
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder presencePenalty(Double v) { this.presencePenalty = v; return this; }
			/**
			 * <p>设置随机种子。</p>
			 * @param v java.lang.Integer 随机种子
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder seed(Integer v) { this.seed = v; return this; }
			/**
			 * <p>设置停止序列。</p>
			 * @param v java.lang.Object 停止序列
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder stop(Object v) { this.stop = v; return this; }
			/**
			 * <p>设置用户标识。</p>
			 * @param v java.lang.String 用户标识
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder user(String v) { this.user = v; return this; }
			/**
			 * <p>设置 SSE 选项。</p>
			 * @param v io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.StreamOptions 流式选项
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Builder 当前构建器
			 */
			public Builder streamOptions(StreamOptions v) { this.streamOptions = v; return this; }

			/**
			 * <p>构建不可变聊天请求。</p>
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 请求对象
			 */
			public ChatRequest build() {
				return new ChatRequest(model, messages, stream, tools, toolChoice,
						maxCompletionTokens, maxTokens, temperature, topP,
						frequencyPenalty, presencePenalty, seed, stop, user, streamOptions);
			}
		}
	}

	/**
	 * <p>聊天会话中的协议消息。</p>
	 * @param role io.github.partmeai.openclaw.api.OpenClawApi.Message.Role 消息角色
	 * @param content java.lang.String 消息文本
	 * @param toolCalls java.util.List&lt;ToolCall&gt; 助手发起的工具调用
	 * @param toolCallId java.lang.String 工具响应关联的调用标识
	 * @param name java.lang.String 工具名称
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(
			@JsonProperty("role") Role role,
			@JsonProperty("content") String content,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls,
			@JsonProperty("tool_call_id") String toolCallId,
			@JsonProperty("name") String name
	) {

		/**
		 * <p>使用消息角色创建构建器。</p>
		 * @param role io.github.partmeai.openclaw.api.OpenClawApi.Message.Role 消息角色
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.Builder 新构建器
		 */
		public static Builder builder(Role role) { return new Builder(role); }

		/** <p>OpenAI 兼容消息角色。</p> */
		public enum Role {
			/** <p>系统指令消息。</p> */
			@JsonProperty("system") SYSTEM,
			/** <p>用户消息。</p> */
			@JsonProperty("user") USER,
			/** <p>助手消息。</p> */
			@JsonProperty("assistant") ASSISTANT,
			/** <p>工具执行结果消息。</p> */
			@JsonProperty("tool") TOOL
		}

		/**
		 * <p>助手发起的函数工具调用或其流式增量片段。</p>
		 * @param index java.lang.Integer 工具调用在当前响应中的索引
		 * @param id java.lang.String 工具调用标识
		 * @param type java.lang.String 工具类型，通常为 {@code function}
		 * @param function io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCallFunction 函数信息
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCall(
				@JsonProperty("index") Integer index,
				@JsonProperty("id") String id,
				@JsonProperty("type") String type,
				@JsonProperty("function") ToolCallFunction function
		) {
			/**
			 * <p>创建不带流式索引的工具调用。</p>
			 * @param id java.lang.String 工具调用标识
			 * @param type java.lang.String 工具类型
			 * @param function io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCallFunction 函数信息
			 */
			public ToolCall(String id, String type, ToolCallFunction function) {
				this(null, id, type, function);
			}
		}

		/**
		 * <p>工具调用中的函数名称和 JSON 参数。</p>
		 * @param name java.lang.String 函数名称
		 * @param arguments java.lang.String JSON 参数文本；流式响应中可能只是片段
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCallFunction(
				@JsonProperty("name") String name,
				@JsonProperty("arguments") String arguments
		) {}

		/** <p>{@link Message} 构建器。</p> */
		public static final class Builder {
			/** <p>消息角色。</p> */
			private final Role role;
			/** <p>消息文本。</p> */
			private String content;
			/** <p>工具调用列表。</p> */
			private List<ToolCall> toolCalls;
			/** <p>关联的工具调用标识。</p> */
			private String toolCallId;
			/** <p>工具名称。</p> */
			private String name;

			/**
			 * <p>创建消息构建器。</p>
			 * @param role io.github.partmeai.openclaw.api.OpenClawApi.Message.Role 消息角色
			 */
			public Builder(Role role) { this.role = role; }
			/**
			 * <p>设置消息文本。</p>
			 * @param v java.lang.String 消息文本
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.Builder 当前构建器
			 */
			public Builder content(String v) { this.content = v; return this; }
			/**
			 * <p>设置工具调用列表。</p>
			 * @param v java.util.List&lt;ToolCall&gt; 工具调用列表
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.Builder 当前构建器
			 */
			public Builder toolCalls(List<ToolCall> v) { this.toolCalls = v; return this; }
			/**
			 * <p>设置关联的工具调用标识。</p>
			 * @param v java.lang.String 工具调用标识
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.Builder 当前构建器
			 */
			public Builder toolCallId(String v) { this.toolCallId = v; return this; }
			/**
			 * <p>设置工具名称。</p>
			 * @param v java.lang.String 工具名称
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.Builder 当前构建器
			 */
			public Builder name(String v) { this.name = v; return this; }
			/**
			 * <p>构建不可变消息。</p>
			 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message 消息对象
			 */
			public Message build() { return new Message(role, content, toolCalls, toolCallId, name); }
		}
	}

	/**
	 * <p>OpenAI 兼容的聊天补全响应。</p>
	 * @param id java.lang.String 响应标识
	 * @param object java.lang.String 响应对象类型
	 * @param created java.lang.Long 创建时间戳
	 * @param model java.lang.String 实际模型或智能体目标
	 * @param choices java.util.List&lt;Choice&gt; 响应选项
	 * @param usage io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Usage token 用量
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponse(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("model") String model,
			@JsonProperty("choices") List<Choice> choices,
			@JsonProperty("usage") Usage usage
	) {

		/**
		 * <p>聊天补全中的单个选项。</p>
		 * @param index java.lang.Integer 选项索引
		 * @param message io.github.partmeai.openclaw.api.OpenClawApi.Message 完整消息
		 * @param delta io.github.partmeai.openclaw.api.OpenClawApi.Message 流式增量消息
		 * @param finishReason java.lang.String 结束原因
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Choice(
				@JsonProperty("index") Integer index,
				@JsonProperty("message") Message message,
				@JsonProperty("delta") Message delta,
				@JsonProperty("finish_reason") String finishReason
		) {}

		/**
		 * <p>聊天请求的 token 用量。</p>
		 * @param promptTokens java.lang.Integer 输入 token 数
		 * @param completionTokens java.lang.Integer 输出 token 数
		 * @param totalTokens java.lang.Integer token 总数
		 * @param promptTokensDetails io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Usage.TokenDetails 输入 token 明细
		 * @param completionTokensDetails io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Usage.TokenDetails 输出 token 明细
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Usage(
				@JsonProperty("prompt_tokens") Integer promptTokens,
				@JsonProperty("completion_tokens") Integer completionTokens,
				@JsonProperty("total_tokens") Integer totalTokens,
				@JsonProperty("prompt_tokens_details") TokenDetails promptTokensDetails,
				@JsonProperty("completion_tokens_details") TokenDetails completionTokensDetails
		) {

			/**
			 * <p>缓存和推理 token 明细。</p>
			 * @param cachedTokens java.lang.Integer 缓存命中的 token 数
			 * @param reasoningTokens java.lang.Integer 推理 token 数
			 */
			@JsonInclude(Include.NON_NULL)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public record TokenDetails(
					@JsonProperty("cached_tokens") Integer cachedTokens,
					@JsonProperty("reasoning_tokens") Integer reasoningTokens
			) {}
		}
	}

	// ========================================================================
	// Request / Response Models — Models
	// ========================================================================

	/**
	 * <p>模型列表响应。</p>
	 * @param object java.lang.String 响应对象类型
	 * @param data java.util.List&lt;ModelData&gt; 模型或智能体目标条目
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListModelResponse(
			@JsonProperty("object") String object,
			@JsonProperty("data") List<ModelData> data
	) {}

	/**
	 * <p>模型列表中的智能体目标条目。</p>
	 * @param id java.lang.String 目标标识
	 * @param object java.lang.String 对象类型
	 * @param created java.lang.Long 创建时间戳
	 * @param ownedBy java.lang.String 所有者
	 * @param permission java.util.List&lt;Object&gt; 权限信息
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelData(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission
	) {}

	/**
	 * <p>单个智能体目标详情响应。</p>
	 * @param id java.lang.String 目标标识
	 * @param object java.lang.String 对象类型
	 * @param created java.lang.Long 创建时间戳
	 * @param ownedBy java.lang.String 所有者
	 * @param permission java.util.List&lt;Object&gt; 权限信息
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelResponse(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy,
			@JsonProperty("permission") List<Object> permission
	) {}

	// ========================================================================
	// Request / Response Models — Embeddings
	// ========================================================================

	/**
	 * <p>OpenAI 兼容的嵌入请求。</p>
	 * @param model java.lang.String 模型或智能体目标标识
	 * @param input java.lang.Object 单个文本或文本列表
	 * @param dimensions java.lang.Integer 期望向量维度
	 * @param user java.lang.String 用户标识
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingsRequest(
			@JsonProperty("model") String model,
			@JsonProperty("input") Object input,
			@JsonProperty("dimensions") Integer dimensions,
			@JsonProperty("user") String user
	) {
		/**
		 * <p>以单个文本创建嵌入请求。</p>
		 * @param model java.lang.String 模型或智能体目标标识
		 * @param input java.lang.String 输入文本
		 */
		public EmbeddingsRequest(String model, String input) {
			this(model, (Object) input, null, null);
		}

		/**
		 * <p>以文本列表创建嵌入请求。</p>
		 * @param model java.lang.String 模型或智能体目标标识
		 * @param input java.util.List&lt;String&gt; 输入文本列表
		 */
		public EmbeddingsRequest(String model, List<String> input) {
			this(model, (Object) input, null, null);
		}
	}

	/**
	 * <p>嵌入响应。</p>
	 * @param object java.lang.String 响应对象类型
	 * @param data java.util.List&lt;EmbeddingData&gt; 嵌入向量条目
	 * @param model java.lang.String 实际模型标识
	 * @param usage io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Usage token 用量
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingsResponse(
			@JsonProperty("object") String object,
			@JsonProperty("data") List<EmbeddingData> data,
			@JsonProperty("model") String model,
			@JsonProperty("usage") ChatResponse.Usage usage
	) {}

	/**
	 * <p>单个嵌入向量条目。</p>
	 * @param object java.lang.String 对象类型
	 * @param index java.lang.Integer 输入位置索引
	 * @param embedding java.util.List&lt;Float&gt; 浮点向量
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddingData(
			@JsonProperty("object") String object,
			@JsonProperty("index") Integer index,
			@JsonProperty("embedding") List<Float> embedding
	) {}

	// ========================================================================
	// Builder
	// ========================================================================

	/**
	 * <p>{@link OpenClawApi} 构建器。</p>
	 *
	 * <p>为同步和响应式客户端克隆调用方提供的 Builder，因此构建 API 客户端时不会直接修改
	 * 传入 Builder 的基础 URL 与默认请求头配置。</p>
	 */
	public static final class Builder {

		/** <p>OpenClaw Gateway 基础地址。</p> */
		private String baseUrl = OpenClawApiConstants.DEFAULT_BASE_URL;
		/** <p>同步 REST 客户端构建器。</p> */
		private RestClient.Builder restClientBuilder = RestClient.builder();
		/** <p>响应式客户端构建器。</p> */
		private WebClient.Builder webClientBuilder = WebClient.builder();
		/** <p>同步请求状态码错误处理器。</p> */
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;
		/** <p>聊天 SSE 解析错误处理器。</p> */
		private SseErrorHandler sseErrorHandler = SseErrorHandler.DEFAULT;
		/** <p>最大并发请求数。</p> */
		private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;

		/**
		 * <p>设置 Gateway 基础地址。</p>
		 * @param baseUrl java.lang.String 非空基础地址
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be null or empty");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * <p>设置同步 REST 客户端构建器。</p>
		 * @param restClientBuilder org.springframework.web.client.RestClient.Builder 客户端构建器
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder cannot be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		/**
		 * <p>设置响应式客户端构建器。</p>
		 * @param webClientBuilder org.springframework.web.reactive.function.client.WebClient.Builder 客户端构建器
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "webClientBuilder cannot be null");
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		/**
		 * <p>设置同步响应错误处理器。</p>
		 * @param responseErrorHandler org.springframework.web.client.ResponseErrorHandler 错误处理器
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			Assert.notNull(responseErrorHandler, "responseErrorHandler cannot be null");
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		/**
		 * <p>设置聊天 SSE 解析错误处理策略。</p>
		 *
		 * <p>默认使用 {@link SseErrorHandler#DEFAULT}；也可使用
		 * {@link SseErrorHandler#LENIENT} 忽略全部解析错误，或使用
		 * {@link SseErrorHandler#STRICT} 传播全部解析错误。</p>
		 * @param sseErrorHandler io.github.partmeai.openclaw.api.SseErrorHandler SSE 错误处理器
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder sseErrorHandler(SseErrorHandler sseErrorHandler) {
			Assert.notNull(sseErrorHandler, "sseErrorHandler cannot be null");
			this.sseErrorHandler = sseErrorHandler;
			return this;
		}

		/**
		 * <p>设置最大并发请求数。</p>
		 * @param maxConcurrentRequests int 大于零的最大并发数
		 * @return io.github.partmeai.openclaw.api.OpenClawApi.Builder 当前构建器
		 */
		public Builder maxConcurrentRequests(int maxConcurrentRequests) {
			Assert.isTrue(maxConcurrentRequests > 0,
					"maxConcurrentRequests must be greater than zero");
			this.maxConcurrentRequests = maxConcurrentRequests;
			return this;
		}

		/**
		 * <p>构建 OpenClaw API 客户端。</p>
		 * @return io.github.partmeai.openclaw.api.OpenClawApi 新客户端实例
		 */
		public OpenClawApi build() {
			return new OpenClawApi(this.baseUrl, this.restClientBuilder,
					this.webClientBuilder, this.responseErrorHandler, this.sseErrorHandler,
					this.maxConcurrentRequests);
		}
	}
}

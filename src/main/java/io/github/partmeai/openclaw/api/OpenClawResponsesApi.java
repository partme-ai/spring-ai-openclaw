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

import io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Tool;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.util.JsonHelper;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * <p>OpenClaw Gateway 的 OpenResponses 兼容 HTTP 客户端。</p>
 *
 * <p>封装 {@code POST /v1/responses}，支持：</p>
 * <ul>
 *   <li>文本、图片和文件等条目式输入</li>
 *   <li>系统指令与客户端函数工具</li>
 *   <li>SSE 流式响应</li>
 *   <li>通过 {@code user} 字段维持会话路由</li>
 * </ul>
 *
 * <p>同步、异步和流式请求共享同一并发门禁。流式请求使用独立的 WebClient 接受
 * {@code text/event-stream}，在订阅时登记活动流，并在完成、失败或取消时释放计数。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
@Slf4j
public final class OpenClawResponsesApi {

	/** <p>客户端默认允许的最大并发请求数。</p> */
	public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 512;

	/** <p>OpenResponses SSE 流结束标记。</p> */
	private static final String SSE_DONE = "[DONE]";

	/**
	 * <p>创建 OpenResponses API 构建器。</p>
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** <p>请求体为空时使用的校验错误消息。</p> */
	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	/** <p>执行同步 JSON 请求的客户端。</p> */
	private final RestClient restClient;

	/** <p>执行非流式响应式请求的客户端。</p> */
	private final WebClient webClient;

	/** <p>执行 SSE 流式请求的客户端。</p> */
	private final WebClient streamingWebClient;

	/** <p>当前处于订阅生命周期内的响应 SSE 流数量。</p> */
	private final AtomicInteger activeStreams = new AtomicInteger();

	/** <p>全部 Responses 请求共享的并发门禁。</p> */
	private final OpenClawRequestLimiter requestLimiter;

	private OpenClawResponsesApi(String baseUrl, RestClient.Builder restClientBuilder,
			WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler,
			int maxConcurrentRequests) {

		this.restClient = restClientBuilder.clone()
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
					headers.setAccept(List.of(MediaType.APPLICATION_JSON));
				})
				.build();

		this.streamingWebClient = webClientBuilder
				.clone()
				.baseUrl(baseUrl)
				.defaultHeaders(headers -> {
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
				})
				.build();

		this.requestLimiter = new OpenClawRequestLimiter(maxConcurrentRequests);
		log.debug("Initialized OpenClaw Responses API client: baseUrl={}, maxConcurrentRequests={}",
				baseUrl, maxConcurrentRequests);
	}

	// --------------------------------------------------------------------------
	// Responses API
	// --------------------------------------------------------------------------

	/**
	 * <p>同步创建 OpenResponses 响应。</p>
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 非流式请求
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
	 */
	public ResponseResult createResponse(ResponseRequest request) {
		return createResponse(request, Map.of());
	}

	/**
	 * <p>携带额外请求头同步创建响应。</p>
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 非流式请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
	 */
	public ResponseResult createResponse(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!Boolean.TRUE.equals(request.stream()), "Stream mode must be disabled for sync calls.");

		var requestSpec = this.restClient.post().uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.execute(() -> requestSpec.body(request)
			.retrieve().body(ResponseResult.class));
	}

	/**
	 * <p>异步创建 OpenResponses 响应。</p>
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 非流式请求
	 * @return reactor.core.publisher.Mono&lt;ResponseResult&gt; 发出响应结果的 Publisher
	 */
	public Mono<ResponseResult> createResponseAsync(ResponseRequest request) {
		return createResponseAsync(request, Map.of());
	}

	/**
	 * <p>携带额外请求头异步创建响应。</p>
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 非流式请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return reactor.core.publisher.Mono&lt;ResponseResult&gt; 受并发门禁保护的响应 Publisher
	 */
	public Mono<ResponseResult> createResponseAsync(ResponseRequest request,
			Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!Boolean.TRUE.equals(request.stream()), "Stream mode must be disabled for sync calls.");
		var requestSpec = this.webClient.post().uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return this.requestLimiter.guard(requestSpec.bodyValue(request)
			.retrieve().bodyToMono(ResponseResult.class));
	}

	/**
	 * <p>创建 OpenResponses SSE 流式响应。</p>
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 已开启流式模式的请求
	 * @return reactor.core.publisher.Flux&lt;ResponseEvent&gt; 响应事件流
	 */
	public Flux<ResponseEvent> streamingResponse(ResponseRequest request) {
		return streamingResponse(request, Map.of());
	}

	/**
	 * <p>携带额外请求头创建 SSE 流式响应。</p>
	 *
	 * <p>流在订阅时计入活动流并占用并发槽位。{@code [DONE]} 只作为终止标记，不参与
	 * JSON 反序列化；所有终止信号都会释放活动流计数和并发槽位。</p>
	 *
	 * @param request io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 已开启流式模式的请求
	 * @param extraHeaders java.util.Map&lt;String, String&gt; 附加请求头
	 * @return reactor.core.publisher.Flux&lt;ResponseEvent&gt; 受并发门禁保护的响应事件流
	 */
	public Flux<ResponseEvent> streamingResponse(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(Boolean.TRUE.equals(request.stream()),
				"Request must set stream=true for streaming calls.");

		var requestSpec = this.streamingWebClient.post()
				.uri("/v1/responses")
				.accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(requestSpec::header);

		// 将活动流登记推迟到订阅时，并为每次订阅保持独立生命周期。
		return this.requestLimiter.guard(Flux.defer(() -> {
			int active = this.activeStreams.incrementAndGet();
			if (log.isTraceEnabled()) {
				log.trace("Opened OpenClaw response stream: activeStreams={}", active);
			}
			return requestSpec
				.bodyValue(request)
				.retrieve()
				.bodyToFlux(String.class)
				// 在 JSON 映射前截断协议结束哨兵。
				.takeUntil(SSE_DONE::equals)
				.filter(data -> !SSE_DONE.equals(data))
				.map(data -> new JsonHelper().fromJson(data, ResponseEvent.class))
				.doOnNext(event -> {
					if (log.isTraceEnabled()) {
						log.trace("SSE event: {}", event);
					}
				})
				.doFinally(signal -> {
					int remaining = this.activeStreams.decrementAndGet();
					if (log.isTraceEnabled()) {
						log.trace("Closed OpenClaw response stream: signal={}, activeStreams={}",
								signal, remaining);
					}
				});
		}));
	}

	/**
	 * <p>获取当前活动的 Responses SSE 流数量。</p>
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
	// Request / Response Models — OpenResponses API
	// --------------------------------------------------------------------------

	/**
	 * <p>OpenResponses 兼容请求体。</p>
	 * @param model java.lang.String OpenClaw 智能体目标标识
	 * @param input java.lang.Object 文本或条目式输入
	 * @param instructions java.lang.String 系统指令
	 * @param tools java.util.List&lt;Tool&gt; 可用函数工具
	 * @param toolChoice java.lang.Object 工具选择策略
	 * @param stream java.lang.Boolean 是否启用 SSE
	 * @param maxOutputTokens java.lang.Integer 最大输出 token 数
	 * @param temperature java.lang.Double 采样温度
	 * @param topP java.lang.Double 核采样概率
	 * @param user java.lang.String 会话路由用户标识
	 * @param previousResponseId java.lang.String 上一响应标识
	 *
	 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api#request-shape-supported">Request Shape</a>
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseRequest(
			@JsonProperty("model") String model,
			@JsonProperty("input") Object input,
			@JsonProperty("instructions") String instructions,
			@JsonProperty("tools") List<Tool> tools,
			@JsonProperty("tool_choice") Object toolChoice,
			@JsonProperty("stream") Boolean stream,
			@JsonProperty("max_output_tokens") Integer maxOutputTokens,
			@JsonProperty("temperature") Double temperature,
			@JsonProperty("top_p") Double topP,
			@JsonProperty("user") String user,
			@JsonProperty("previous_response_id") String previousResponseId
	) {

		/**
		 * <p>使用必需的模型标识创建请求构建器。</p>
		 * @param model java.lang.String OpenClaw 智能体目标标识
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 新构建器
		 */
		public static Builder builder(String model) {
			return new Builder(model);
		}

		/** <p>{@link ResponseRequest} 构建器。</p> */
		public static final class Builder {

			/** <p>必需的智能体目标标识。</p> */
			private final String model;
			/** <p>文本或条目式输入。</p> */
			private Object input;
			/** <p>系统指令。</p> */
			private String instructions;
			/** <p>函数工具列表。</p> */
			private List<Tool> tools;
			/** <p>工具选择策略。</p> */
			private Object toolChoice;
			/** <p>是否启用流式响应。</p> */
			private Boolean stream = false;
			/** <p>最大输出 token 数。</p> */
			private Integer maxOutputTokens;
			/** <p>采样温度。</p> */
			private Double temperature;
			/** <p>核采样概率。</p> */
			private Double topP;
			/** <p>用户标识。</p> */
			private String user;
			/** <p>上一响应标识。</p> */
			private String previousResponseId;

			/**
			 * <p>创建请求构建器。</p>
			 * @param model java.lang.String 非空智能体目标标识
			 * @throws java.lang.IllegalArgumentException 当模型标识为 {@code null} 时抛出
			 */
			public Builder(String model) {
				Assert.notNull(model, "The model can not be null.");
				this.model = model;
			}

			/**
			 * <p>设置通用输入。</p>
			 * @param input java.lang.Object 文本或条目式输入
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder input(Object input) { this.input = input; return this; }
			/**
			 * <p>设置纯文本输入。</p>
			 * @param text java.lang.String 输入文本
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder input(String text) { this.input = text; return this; }
			/**
			 * <p>设置条目式输入。</p>
			 * @param items java.util.List&lt;Object&gt; 输入条目
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder input(List<Object> items) { this.input = items; return this; }
			/**
			 * <p>设置系统指令。</p>
			 * @param instructions java.lang.String 系统指令
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder instructions(String instructions) { this.instructions = instructions; return this; }
			/**
			 * <p>设置函数工具。</p>
			 * @param tools java.util.List&lt;Tool&gt; 函数工具
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder tools(List<Tool> tools) { this.tools = tools; return this; }
			/**
			 * <p>设置工具选择策略。</p>
			 * @param toolChoice java.lang.Object 工具选择策略
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder toolChoice(Object toolChoice) { this.toolChoice = toolChoice; return this; }
			/**
			 * <p>设置是否启用 SSE。</p>
			 * @param stream boolean 是否流式
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder stream(boolean stream) { this.stream = stream; return this; }
			/**
			 * <p>设置最大输出 token 数。</p>
			 * @param v java.lang.Integer 最大输出 token 数
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder maxOutputTokens(Integer v) { this.maxOutputTokens = v; return this; }
			/**
			 * <p>设置采样温度。</p>
			 * @param v java.lang.Double 采样温度
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder temperature(Double v) { this.temperature = v; return this; }
			/**
			 * <p>设置核采样概率。</p>
			 * @param v java.lang.Double 核采样概率
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder topP(Double v) { this.topP = v; return this; }
			/**
			 * <p>设置用户标识。</p>
			 * @param v java.lang.String 用户标识
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder user(String v) { this.user = v; return this; }
			/**
			 * <p>设置上一响应标识。</p>
			 * @param v java.lang.String 上一响应标识
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest.Builder 当前构建器
			 */
			public Builder previousResponseId(String v) { this.previousResponseId = v; return this; }

			/**
			 * <p>构建不可变响应请求。</p>
			 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest 请求对象
			 */
			public ResponseRequest build() {
				return new ResponseRequest(model, input, instructions, tools, toolChoice,
						stream, maxOutputTokens, temperature, topP, user, previousResponseId);
			}
		}
	}

	/**
	 * <p>OpenResponses API 的完整响应结果。</p>
	 * @param id java.lang.String 响应标识
	 * @param object java.lang.String 对象类型
	 * @param status java.lang.String 响应状态
	 * @param completedAt java.lang.Long 完成时间戳
	 * @param createdAt java.lang.Long 创建时间戳
	 * @param model java.lang.String 实际模型或智能体目标
	 * @param output java.util.List&lt;OutputItem&gt; 输出条目
	 * @param usage io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseUsage token 用量
	 * @param error io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseError 错误信息
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseResult(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("status") String status,
			@JsonProperty("completed_at") Long completedAt,
			@JsonProperty("created_at") Long createdAt,
			@JsonProperty("model") String model,
			@JsonProperty("output") List<OutputItem> output,
			@JsonProperty("usage") ResponseUsage usage,
			@JsonProperty("error") ResponseError error
	) {}

	/**
	 * <p>响应中的输出条目。</p>
	 * @param id java.lang.String 条目标识
	 * @param type java.lang.String 条目类型
	 * @param status java.lang.String 条目状态
	 * @param content java.util.List&lt;OutputContent&gt; 内容片段
	 * @param role java.lang.String 消息角色
	 * @param functionCall io.github.partmeai.openclaw.api.OpenClawResponsesApi.FunctionCall 函数调用
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OutputItem(
			@JsonProperty("id") String id,
			@JsonProperty("type") String type,
			@JsonProperty("status") String status,
			@JsonProperty("content") List<OutputContent> content,
			@JsonProperty("role") String role,
			@JsonProperty("function_call") FunctionCall functionCall
	) {}

	/**
	 * <p>文本、图片等输出内容片段。</p>
	 * @param type java.lang.String 内容类型
	 * @param text java.lang.String 文本内容
	 * @param image java.lang.String 图片内容或引用
	 * @param source io.github.partmeai.openclaw.api.OpenClawResponsesApi.ContentSource 外部内容来源
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OutputContent(
			@JsonProperty("type") String type,
			@JsonProperty("text") String text,
			@JsonProperty("image") String image,
			@JsonProperty("source") ContentSource source
	) {}

	/**
	 * <p>图片或文件的外部内容来源。</p>
	 * @param type java.lang.String 来源类型
	 * @param url java.lang.String 来源 URL
	 * @param mediaType java.lang.String 媒体类型
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ContentSource(
			@JsonProperty("type") String type,
			@JsonProperty("url") String url,
			@JsonProperty("media_type") String mediaType
	) {}

	/**
	 * <p>模型请求客户端执行的函数调用。</p>
	 * @param id java.lang.String 调用标识
	 * @param name java.lang.String 函数名称
	 * @param arguments java.lang.String JSON 参数文本
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FunctionCall(
			@JsonProperty("id") String id,
			@JsonProperty("name") String name,
			@JsonProperty("arguments") String arguments
	) {}

	/**
	 * <p>OpenResponses token 用量。</p>
	 * @param inputTokens java.lang.Integer 输入 token 数
	 * @param outputTokens java.lang.Integer 输出 token 数
	 * @param totalTokens java.lang.Integer token 总数
	 * @param inputTokensDetails io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseUsage.TokenDetails 输入明细
	 * @param outputTokensDetails io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseUsage.TokenDetails 输出明细
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseUsage(
			@JsonProperty("input_tokens") Integer inputTokens,
			@JsonProperty("output_tokens") Integer outputTokens,
			@JsonProperty("total_tokens") Integer totalTokens,
			@JsonProperty("input_tokens_details") TokenDetails inputTokensDetails,
			@JsonProperty("output_tokens_details") TokenDetails outputTokensDetails
	) {

		/**
		 * <p>缓存与推理 token 明细。</p>
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

	/**
	 * <p>OpenResponses 错误信息。</p>
	 * @param type java.lang.String 错误类型
	 * @param message java.lang.String 错误说明
	 * @param code java.lang.String 错误码
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseError(
			@JsonProperty("type") String type,
			@JsonProperty("message") String message,
			@JsonProperty("code") String code
	) {}

	// --------------------------------------------------------------------------
	// Streaming SSE Event Models
	// --------------------------------------------------------------------------

	/**
	 * <p>OpenResponses 流式响应事件。</p>
	 * @param event java.lang.String 事件类型
	 * @param data java.lang.Object 事件负载
	 *
	 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api#streaming-sse">Streaming SSE</a>
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseEvent(
			@JsonProperty("event") String event,
			@JsonProperty("data") Object data
	) {

		/** <p>响应已创建事件。</p> */
		public static final String EVENT_RESPONSE_CREATED = "response.created";
		/** <p>响应处理中事件。</p> */
		public static final String EVENT_RESPONSE_IN_PROGRESS = "response.in_progress";
		/** <p>输出条目已添加事件。</p> */
		public static final String EVENT_OUTPUT_ITEM_ADDED = "response.output_item.added";
		/** <p>内容片段已添加事件。</p> */
		public static final String EVENT_CONTENT_PART_ADDED = "response.content_part.added";
		/** <p>输出文本增量事件。</p> */
		public static final String EVENT_OUTPUT_TEXT_DELTA = "response.output_text.delta";
		/** <p>输出文本结束事件。</p> */
		public static final String EVENT_OUTPUT_TEXT_DONE = "response.output_text.done";
		/** <p>内容片段结束事件。</p> */
		public static final String EVENT_CONTENT_PART_DONE = "response.content_part.done";
		/** <p>输出条目结束事件。</p> */
		public static final String EVENT_OUTPUT_ITEM_DONE = "response.output_item.done";
		/** <p>响应完成事件。</p> */
		public static final String EVENT_RESPONSE_COMPLETED = "response.completed";
		/** <p>响应失败事件。</p> */
		public static final String EVENT_RESPONSE_FAILED = "response.failed";
	}

	// --------------------------------------------------------------------------
	// Input Item Models (for constructing requests)
	// --------------------------------------------------------------------------

	/**
	 * <p>构造 OpenResponses 条目式输入的工具类。</p>
	 *
	 * <p>各工厂方法返回符合协议请求形状的不可变嵌套映射。</p>
	 */
	public static final class InputItems {

		/** <p>禁止实例化工具类。</p> */
		private InputItems() {}

		/**
		 * <p>创建文本消息输入条目。</p>
		 * @param role java.lang.String 消息角色
		 * @param content java.lang.String 文本内容
		 * @return java.util.Map&lt;String, Object&gt; 协议消息条目
		 */
		public static Map<String, Object> textMessage(String role, String content) {
			return Map.of(
				"type", "message",
				"role", role,
				"content", List.of(Map.of("type", "input_text", "text", content))
			);
		}

		/**
		 * <p>通过 URL 创建用户图片输入条目。</p>
		 * @param url java.lang.String 图片 URL
		 * @return java.util.Map&lt;String, Object&gt; 协议图片消息条目
		 */
		public static Map<String, Object> imageFromUrl(String url) {
			return Map.of(
				"type", "message",
				"role", "user",
				"content", List.of(Map.of(
					"type", "input_image",
					"source", Map.of("type", "url", "url", url)
				))
			);
		}

		/**
		 * <p>通过 Base64 数据创建用户图片输入条目。</p>
		 * @param mediaType java.lang.String 图片媒体类型
		 * @param data java.lang.String Base64 数据
		 * @return java.util.Map&lt;String, Object&gt; 协议图片消息条目
		 */
		public static Map<String, Object> imageFromBase64(String mediaType, String data) {
			return Map.of(
				"type", "message",
				"role", "user",
				"content", List.of(Map.of(
					"type", "input_image",
					"source", Map.of("type", "base64", "media_type", mediaType, "data", data)
				))
			);
		}

		/**
		 * <p>通过 URL 创建用户文件输入条目。</p>
		 * @param url java.lang.String 文件 URL
		 * @param mediaType java.lang.String 文件媒体类型
		 * @param filename java.lang.String 文件名
		 * @return java.util.Map&lt;String, Object&gt; 协议文件消息条目
		 */
		public static Map<String, Object> fileFromUrl(String url, String mediaType, String filename) {
			return Map.of(
				"type", "message",
				"role", "user",
				"content", List.of(Map.of(
					"type", "input_file",
					"source", Map.of(
						"type", "url",
						"url", url,
						"media_type", mediaType,
						"filename", filename
					)
				))
			);
		}

		/**
		 * <p>通过 Base64 数据创建用户文件输入条目。</p>
		 * @param mediaType java.lang.String 文件媒体类型
		 * @param data java.lang.String Base64 数据
		 * @param filename java.lang.String 文件名
		 * @return java.util.Map&lt;String, Object&gt; 协议文件消息条目
		 */
		public static Map<String, Object> fileFromBase64(String mediaType, String data, String filename) {
			return Map.of(
				"type", "message",
				"role", "user",
				"content", List.of(Map.of(
					"type", "input_file",
					"source", Map.of(
						"type", "base64",
						"media_type", mediaType,
						"data", data,
						"filename", filename
					)
				))
			);
		}

		/**
		 * <p>创建用于回传工具结果的函数调用输出条目。</p>
		 * @param callId java.lang.String 原函数调用标识
		 * @param output java.lang.String 工具输出文本
		 * @return java.util.Map&lt;String, Object&gt; 函数调用输出条目
		 */
		public static Map<String, Object> functionCallOutput(String callId, String output) {
			return Map.of(
				"type", "function_call_output",
				"call_id", callId,
				"output", output
			);
		}
	}

	// --------------------------------------------------------------------------
	// Builder
	// --------------------------------------------------------------------------

	/** <p>{@link OpenClawResponsesApi} 构建器。</p> */
	public static final class Builder {

		/** <p>OpenClaw Gateway 基础地址。</p> */
		private String baseUrl = io.github.partmeai.openclaw.api.common.OpenClawApiConstants.DEFAULT_BASE_URL;
		/** <p>同步 REST 客户端构建器。</p> */
		private RestClient.Builder restClientBuilder = RestClient.builder();
		/** <p>响应式客户端构建器。</p> */
		private WebClient.Builder webClientBuilder = WebClient.builder();
		/** <p>同步响应错误处理器。</p> */
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;
		/** <p>最大并发请求数。</p> */
		private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;

		/**
		 * <p>设置 Gateway 基础地址。</p>
		 * @param baseUrl java.lang.String 非空基础地址
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 当前构建器
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be null or empty");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * <p>设置同步 REST 客户端构建器。</p>
		 * @param restClientBuilder org.springframework.web.client.RestClient.Builder 客户端构建器
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 当前构建器
		 */
		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder cannot be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		/**
		 * <p>设置响应式客户端构建器。</p>
		 * @param webClientBuilder org.springframework.web.reactive.function.client.WebClient.Builder 客户端构建器
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 当前构建器
		 */
		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "webClientBuilder cannot be null");
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		/**
		 * <p>设置同步响应错误处理器。</p>
		 * @param responseErrorHandler org.springframework.web.client.ResponseErrorHandler 错误处理器
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 当前构建器
		 */
		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			Assert.notNull(responseErrorHandler, "responseErrorHandler cannot be null");
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		/**
		 * <p>设置最大并发请求数。</p>
		 * @param maxConcurrentRequests int 大于零的最大并发数
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.Builder 当前构建器
		 */
		public Builder maxConcurrentRequests(int maxConcurrentRequests) {
			Assert.isTrue(maxConcurrentRequests > 0,
					"maxConcurrentRequests must be greater than zero");
			this.maxConcurrentRequests = maxConcurrentRequests;
			return this;
		}

		/**
		 * <p>构建 OpenResponses API 客户端。</p>
		 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi 新客户端实例
		 */
		public OpenClawResponsesApi build() {
			return new OpenClawResponsesApi(this.baseUrl, this.restClientBuilder,
					this.webClientBuilder, this.responseErrorHandler,
					this.maxConcurrentRequests);
		}
	}
}

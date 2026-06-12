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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.partmeai.openclaw.api.common.OpenClawApiConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Java Client for the OpenClaw Gateway OpenAI-compatible HTTP API.
 * <p>
 * Calls {@code /v1/chat/completions}, {@code /v1/models}, {@code /v1/embeddings},
 * and {@code /v1/responses} as described in the Gateway documentation.
 * <p>
 * The {@code model} field uses OpenClaw agent-target routing:
 * {@code openclaw/default}, {@code openclaw/<agentId>}.
 * Use HTTP headers ({@code x-openclaw-model}, {@code x-openclaw-session-key}, etc.)
 * to control backend model override, session routing, and channel context.
 *
 * @author Loong Wan
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
public final class OpenClawApi {

	public static Builder builder() {
		return new Builder();
	}

	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	private static final Logger logger = LoggerFactory.getLogger(OpenClawApi.class);

	private final RestClient restClient;

	private final WebClient webClient;

	private OpenClawApi(String baseUrl, RestClient.Builder restClientBuilder,
			WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler) {
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
	}

	// --------------------------------------------------------------------------
	// Chat Completions
	// --------------------------------------------------------------------------

	/**
	 * Generate the next message in a chat with a provided model (non-streaming).
	 * @param chatRequest Chat request with stream=false.
	 * @return Chat response.
	 */
	public ChatResponse chat(ChatRequest chatRequest) {
		return chat(chatRequest, Map.of());
	}

	/**
	 * Generate the next message in a chat with a provided model (non-streaming),
	 * with additional HTTP headers (e.g. x-openclaw-* headers).
	 * @param chatRequest Chat request with stream=false.
	 * @param extraHeaders Additional HTTP headers to include in the request.
	 * @return Chat response.
	 */
	public ChatResponse chat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!chatRequest.stream(), "Stream mode must be disabled.");

		var requestSpec = this.restClient.post()
			.uri("/v1/chat/completions");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec
			.body(chatRequest)
			.retrieve()
			.body(ChatResponse.class);
	}

	/**
	 * Streaming (SSE) response for the chat completion request.
	 * Handles SSE delta events and merges them into complete chat responses.
	 * @param chatRequest Chat request with stream=true.
	 * @return Chat response as a {@link Flux} stream.
	 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#streaming-sse">OpenClaw Streaming SSE</a>
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest) {
		return streamingChat(chatRequest, Map.of());
	}

	/**
	 * Streaming (SSE) response for the chat completion request,
	 * with additional HTTP headers (e.g. x-openclaw-* headers).
	 * @param chatRequest Chat request with stream=true.
	 * @param extraHeaders Additional HTTP headers to include in the request.
	 * @return Chat response as a {@link Flux} stream.
	 */
	public Flux<ChatResponse> streamingChat(ChatRequest chatRequest, Map<String, String> extraHeaders) {
		Assert.notNull(chatRequest, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(chatRequest.stream(), "Request must set the stream property to true.");

		var requestSpec = this.webClient.post()
			.uri("/v1/chat/completions")
			.accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(requestSpec::header);

		return requestSpec
			.body(Mono.just(chatRequest), ChatRequest.class)
			.retrieve()
			.bodyToFlux(ChatResponse.class)
			.handle((chunk, sink) -> {
				if (logger.isTraceEnabled()) {
					logger.trace("SSE chunk: {}", chunk);
				}
				if (chunk.choices() != null && !chunk.choices().isEmpty()) {
					sink.next(chunk);
				}
			});
	}

	// --------------------------------------------------------------------------
	// Responses
	// --------------------------------------------------------------------------

	/**
	 * Call the OpenAI-compatible {@code /v1/responses} endpoint.
	 * <p>
	 * More agent-native clients increasingly prefer this endpoint over
	 * {@code /v1/chat/completions}.
	 * @param responsesRequest The responses request body (as a generic Map for flexibility).
	 * @return The responses response body.
	 */
	public Map<String, Object> responses(Map<String, Object> responsesRequest) {
		return responses(responsesRequest, Map.of());
	}

	/**
	 * Call {@code /v1/responses} with additional HTTP headers.
	 */
	public Map<String, Object> responses(Map<String, Object> responsesRequest, Map<String, String> extraHeaders) {
		Assert.notNull(responsesRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post()
			.uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec
			.body(responsesRequest)
			.retrieve()
			.body(Map.class);
	}

	// --------------------------------------------------------------------------
	// Models
	// --------------------------------------------------------------------------

	/**
	 * List agent targets available on the OpenClaw Gateway.
	 * Returns agent-target ids: {@code openclaw}, {@code openclaw/default},
	 * and {@code openclaw/<agentId>} entries.
	 */
	public ListModelResponse listModels() {
		return this.restClient.get()
			.uri("/v1/models")
			.retrieve()
			.body(ListModelResponse.class);
	}

	/**
	 * Show information about a specific agent target on the OpenClaw Gateway.
	 * @param modelId The agent-target model id (e.g. "openclaw/default").
	 */
	public ModelResponse getModel(String modelId) {
		Assert.hasText(modelId, "modelId must not be empty");
		return this.restClient.get()
			.uri("/v1/models/{id}", modelId)
			.retrieve()
			.body(ModelResponse.class);
	}

	// --------------------------------------------------------------------------
	// Embeddings
	// --------------------------------------------------------------------------

	/**
	 * Generate embeddings using the OpenClaw Gateway.
	 * @param embeddingsRequest Embedding request.
	 * @return Embeddings response.
	 */
	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest) {
		return embed(embeddingsRequest, Map.of());
	}

	/**
	 * Generate embeddings using the OpenClaw Gateway, with additional headers.
	 * @param embeddingsRequest Embedding request.
	 * @param extraHeaders Additional HTTP headers.
	 * @return Embeddings response.
	 */
	public EmbeddingsResponse embed(EmbeddingsRequest embeddingsRequest, Map<String, String> extraHeaders) {
		Assert.notNull(embeddingsRequest, REQUEST_BODY_NULL_ERROR);
		var requestSpec = this.restClient.post()
			.uri("/v1/embeddings");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec
			.body(embeddingsRequest)
			.retrieve()
			.body(EmbeddingsResponse.class);
	}

	// ========================================================================
	// Request / Response Models — Chat Completions
	// ========================================================================

	/**
	 * OpenAI-compatible Chat Completions request body.
	 * <p>
	 * The {@code model} field uses OpenClaw agent-target routing
	 * ({@code openclaw/default}, {@code openclaw/<agentId>}, etc.).
	 * Backend provider/model overrides go in the {@code x-openclaw-model} HTTP header.
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

		public static Builder builder(String model) {
			return new Builder(model);
		}

		/**
		 * Stream options for chat completion requests.
		 */
		@JsonInclude(Include.NON_NULL)
		public record StreamOptions(
				@JsonProperty("include_usage") Boolean includeUsage
		) {}

		/**
		 * Represents a tool the model may call. Currently, only functions are supported.
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Tool(
				@JsonProperty("type") Type type,
				@JsonProperty("function") Function function) {

			public Tool(Function function) {
				this(Type.FUNCTION, function);
			}

			public enum Type {
				@JsonProperty("function") FUNCTION
			}

			/**
			 * Function definition.
			 */
			public record Function(
					@JsonProperty("name") String name,
					@JsonProperty("description") String description,
					@JsonProperty("parameters") Map<String, Object> parameters) {

				public Function(String name, String description, Map<String, Object> parameters) {
					this.name = name;
					this.description = description;
					this.parameters = parameters;
				}
			}
		}

		public static final class Builder {

			private final String model;
			private List<Message> messages = List.of();
			private boolean stream = false;
			private List<Tool> tools = List.of();
			private Object toolChoice;
			private Integer maxCompletionTokens;
			private Integer maxTokens;
			private Double temperature;
			private Double topP;
			private Double frequencyPenalty;
			private Double presencePenalty;
			private Integer seed;
			private Object stop;
			private String user;
			private StreamOptions streamOptions;

			public Builder(String model) {
				Assert.notNull(model, "The model can not be null.");
				this.model = model;
			}

			public Builder messages(List<Message> messages) {
				this.messages = messages;
				return this;
			}

			public Builder stream(boolean stream) {
				this.stream = stream;
				return this;
			}

			public Builder tools(List<Tool> tools) {
				this.tools = tools;
				return this;
			}

			public Builder toolChoice(Object toolChoice) {
				this.toolChoice = toolChoice;
				return this;
			}

			public Builder maxCompletionTokens(Integer maxCompletionTokens) {
				this.maxCompletionTokens = maxCompletionTokens;
				return this;
			}

			public Builder maxTokens(Integer maxTokens) {
				this.maxTokens = maxTokens;
				return this;
			}

			public Builder temperature(Double temperature) {
				this.temperature = temperature;
				return this;
			}

			public Builder topP(Double topP) {
				this.topP = topP;
				return this;
			}

			public Builder frequencyPenalty(Double frequencyPenalty) {
				this.frequencyPenalty = frequencyPenalty;
				return this;
			}

			public Builder presencePenalty(Double presencePenalty) {
				this.presencePenalty = presencePenalty;
				return this;
			}

			public Builder seed(Integer seed) {
				this.seed = seed;
				return this;
			}

			public Builder stop(Object stop) {
				this.stop = stop;
				return this;
			}

			public Builder user(String user) {
				this.user = user;
				return this;
			}

			public Builder streamOptions(StreamOptions streamOptions) {
				this.streamOptions = streamOptions;
				return this;
			}

			public ChatRequest build() {
				return new ChatRequest(model, messages, stream, tools, toolChoice, maxCompletionTokens, maxTokens,
						temperature, topP, frequencyPenalty, presencePenalty, seed, stop, user, streamOptions);
			}
		}
	}

	/**
	 * A message in a chat conversation.
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

		public static Builder builder(Role role) {
			return new Builder(role);
		}

		/**
		 * The role of the message in the conversation.
		 */
		public enum Role {
			@JsonProperty("system") SYSTEM,
			@JsonProperty("user") USER,
			@JsonProperty("assistant") ASSISTANT,
			@JsonProperty("tool") TOOL
		}

		/**
		 * A tool call made by the model.
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCall(
				@JsonProperty("id") String id,
				@JsonProperty("type") String type,
				@JsonProperty("function") ToolCallFunction function
		) {}

		/**
		 * The function call details.
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record ToolCallFunction(
				@JsonProperty("name") String name,
				@JsonProperty("arguments") String arguments
		) {}

		public static final class Builder {

			private final Role role;
			private String content;
			private List<ToolCall> toolCalls;
			private String toolCallId;
			private String name;

			public Builder(Role role) {
				this.role = role;
			}

			public Builder content(String content) {
				this.content = content;
				return this;
			}

			public Builder toolCalls(List<ToolCall> toolCalls) {
				this.toolCalls = toolCalls;
				return this;
			}

			public Builder toolCallId(String toolCallId) {
				this.toolCallId = toolCallId;
				return this;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Message build() {
				return new Message(role, content, toolCalls, toolCallId, name);
			}
		}
	}

	/**
	 * OpenAI-compatible Chat Completions response.
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
		 * A single completion choice. In non-streaming mode, {@code message} is populated.
		 * In streaming mode, {@code delta} contains the incremental update.
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
		 * Token usage statistics.
		 */
		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Usage(
				@JsonProperty("prompt_tokens") Integer promptTokens,
				@JsonProperty("completion_tokens") Integer completionTokens,
				@JsonProperty("total_tokens") Integer totalTokens
		) {}
	}

	// ========================================================================
	// Request / Response Models — Models
	// ========================================================================

	/**
	 * Response from GET /v1/models — lists agent-target ids.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListModelResponse(
			@JsonProperty("object") String object,
			@JsonProperty("data") List<ModelData> data
	) {}

	/**
	 * An individual model entry in the list.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelData(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy
	) {}

	/**
	 * Response from GET /v1/models/{id}.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelResponse(
			@JsonProperty("id") String id,
			@JsonProperty("object") String object,
			@JsonProperty("created") Long created,
			@JsonProperty("owned_by") String ownedBy
	) {}

	// ========================================================================
	// Request / Response Models — Embeddings
	// ========================================================================

	/**
	 * OpenAI-compatible embeddings request.
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
		 * Convenience constructor for single string input.
		 */
		public EmbeddingsRequest(String model, String input) {
			this(model, (Object) input, null, null);
		}

		/**
		 * Convenience constructor for list-of-strings input.
		 */
		public EmbeddingsRequest(String model, List<String> input) {
			this(model, (Object) input, null, null);
		}
	}

	/**
	 * OpenAI-compatible embeddings response.
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
	 * A single embedding vector entry.
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

	public static final class Builder {

		private String baseUrl = OpenClawApiConstants.DEFAULT_BASE_URL;

		private RestClient.Builder restClientBuilder = RestClient.builder();

		private WebClient.Builder webClientBuilder = WebClient.builder();

		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be null or empty");
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder cannot be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
			Assert.notNull(webClientBuilder, "webClientBuilder cannot be null");
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder responseErrorHandler(ResponseErrorHandler responseErrorHandler) {
			Assert.notNull(responseErrorHandler, "responseErrorHandler cannot be null");
			this.responseErrorHandler = responseErrorHandler;
			return this;
		}

		public OpenClawApi build() {
			return new OpenClawApi(this.baseUrl, this.restClientBuilder, this.webClientBuilder,
					this.responseErrorHandler);
		}
	}
}

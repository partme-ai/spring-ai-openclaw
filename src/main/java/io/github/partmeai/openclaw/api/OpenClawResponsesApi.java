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

import io.github.partmeai.openclaw.api.OpenClawApi.Tool;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Java Client for the OpenClaw Gateway OpenResponses-compatible HTTP API.
 * <p>
 * Calls {@code POST /v1/responses} with support for:
 * <ul>
 *   <li>Item-based input (text, images, files)</li>
 *   <li>System instructions</li>
 *   <li>Client-side function tools</li>
 *   <li>Streaming SSE responses</li>
 *   <li>Session management via {@code user} field</li>
 * </ul>
 *
 * @author Loong Wan
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
@Slf4j
public final class OpenClawResponsesApi {

	public static Builder builder() {
		return new Builder();
	}

	public static final String REQUEST_BODY_NULL_ERROR = "The request body can not be null.";

	private final WebClient webClient;

	private final WebClient streamingWebClient;

	private final SseErrorHandler sseErrorHandler;

	private OpenClawResponsesApi(String baseUrl, WebClient.Builder webClientBuilder,
			ResponseErrorHandler responseErrorHandler, SseErrorHandler sseErrorHandler) {

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
					headers.setAccept(MediaType.TEXT_EVENT_STREAM);
				})
				.build();

		this.sseErrorHandler = sseErrorHandler;
	}

	// --------------------------------------------------------------------------
	// Responses API
	// --------------------------------------------------------------------------

	/**
	 * Create a response using the OpenResponses API.
	 */
	public ResponseResult createResponse(ResponseRequest request) {
		return createResponse(request, Map.of());
	}

	/**
	 * Create a response with extra headers.
	 */
	public ResponseResult createResponse(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(!Boolean.TRUE.equals(request.stream()), "Stream mode must be disabled for sync calls.");

		var requestSpec = this.webClient.post()
				.uri("/v1/responses");
		extraHeaders.forEach(requestSpec::header);
		return requestSpec.body(Mono.just(request), ResponseRequest.class)
				.retrieve()
				.bodyToMono(ResponseResult.class)
				.block();
	}

	/**
	 * Create a streaming response (SSE) using the OpenResponses API.
	 */
	public Flux<ResponseEvent> streamingResponse(ResponseRequest request) {
		return streamingResponse(request, Map.of());
	}

	/**
	 * Create a streaming response with extra headers.
	 */
	public Flux<ResponseEvent> streamingResponse(ResponseRequest request, Map<String, String> extraHeaders) {
		Assert.notNull(request, REQUEST_BODY_NULL_ERROR);
		Assert.isTrue(Boolean.TRUE.equals(request.stream()),
				"Request must set stream=true for streaming calls.");

		var requestSpec = this.streamingWebClient.post()
				.uri("/v1/responses")
				.accept(MediaType.TEXT_EVENT_STREAM);
		extraHeaders.forEach(requestSpec::header);

		return requestSpec
				.body(Mono.just(request), ResponseRequest.class)
				.retrieve()
				.bodyToFlux(ResponseEvent.class)
				.onErrorResume(sseErrorHandler::handle)
				.handle((event, sink) -> {
					if (log.isTraceEnabled()) {
						log.trace("SSE event: {}", event);
					}
					sink.next(event);
				});
	}

	// --------------------------------------------------------------------------
	// Request / Response Models — OpenResponses API
	// --------------------------------------------------------------------------

	/**
	 * OpenResponses-compatible request body.
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

		public static Builder builder(String model) {
			return new Builder(model);
		}

		/**
		 * Builder for ResponseRequest.
		 */
		public static final class Builder {

			private final String model;
			private Object input;
			private String instructions;
			private List<Tool> tools;
			private Object toolChoice;
			private Boolean stream = false;
			private Integer maxOutputTokens;
			private Double temperature;
			private Double topP;
			private String user;
			private String previousResponseId;

			public Builder(String model) {
				Assert.notNull(model, "The model can not be null.");
				this.model = model;
			}

			public Builder input(Object input) { this.input = input; return this; }
			public Builder input(String text) { this.input = text; return this; }
			public Builder input(List<Object> items) { this.input = items; return this; }
			public Builder instructions(String instructions) { this.instructions = instructions; return this; }
			public Builder tools(List<Tool> tools) { this.tools = tools; return this; }
			public Builder toolChoice(Object toolChoice) { this.toolChoice = toolChoice; return this; }
			public Builder stream(boolean stream) { this.stream = stream; return this; }
			public Builder maxOutputTokens(Integer v) { this.maxOutputTokens = v; return this; }
			public Builder temperature(Double v) { this.temperature = v; return this; }
			public Builder topP(Double v) { this.topP = v; return this; }
			public Builder user(String v) { this.user = v; return this; }
			public Builder previousResponseId(String v) { this.previousResponseId = v; return this; }

			public ResponseRequest build() {
				return new ResponseRequest(model, input, instructions, tools, toolChoice,
						stream, maxOutputTokens, temperature, topP, user, previousResponseId);
			}
		}
	}

	/**
	 * Response result from the OpenResponses API.
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
	 * Output item types.
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
	 * Output content (text, images, etc.).
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
	 * Content source (for images/files from URLs).
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ContentSource(
			@JsonProperty("type") String type,
			@JsonProperty("url") String url,
			@JsonProperty("media_type") String mediaType
	) {}

	/**
	 * Function call output item.
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FunctionCall(
			@JsonProperty("id") String id,
			@JsonProperty("name") String name,
			@JsonProperty("arguments") String arguments
	) {}

	/**
	 * Usage information.
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

		@JsonInclude(Include.NON_NULL)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record TokenDetails(
				@JsonProperty("cached_tokens") Integer cachedTokens,
				@JsonProperty("reasoning_tokens") Integer reasoningTokens
		) {}
	}

	/**
	 * Error information.
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
	 * SSE event types for streaming responses.
	 *
	 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api#streaming-sse">Streaming SSE</a>
	 */
	@JsonInclude(Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResponseEvent(
			@JsonProperty("event") String event,
			@JsonProperty("data") Object data
	) {

		// Event type constants
		public static final String EVENT_RESPONSE_CREATED = "response.created";
		public static final String EVENT_RESPONSE_IN_PROGRESS = "response.in_progress";
		public static final String EVENT_OUTPUT_ITEM_ADDED = "response.output_item.added";
		public static final String EVENT_CONTENT_PART_ADDED = "response.content_part.added";
		public static final String EVENT_OUTPUT_TEXT_DELTA = "response.output_text.delta";
		public static final String EVENT_OUTPUT_TEXT_DONE = "response.output_text.done";
		public static final String EVENT_CONTENT_PART_DONE = "response.content_part.done";
		public static final String EVENT_OUTPUT_ITEM_DONE = "response.output_item.done";
		public static final String EVENT_RESPONSE_COMPLETED = "response.completed";
		public static final String EVENT_RESPONSE_FAILED = "response.failed";
	}

	// --------------------------------------------------------------------------
	// Input Item Models (for constructing requests)
	// --------------------------------------------------------------------------

	/**
	 * Input item types for building requests.
	 */
	public static final class InputItems {

		private InputItems() {}

		/**
		 * Create a text message input item.
		 */
		public static Map<String, Object> textMessage(String role, String content) {
			return Map.of(
				"type", "message",
				"role", role,
				"content", List.of(Map.of("type", "input_text", "text", content))
			);
		}

		/**
		 * Create an image input item from URL.
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
		 * Create an image input item from base64.
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
		 * Create a file input item from URL.
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
		 * Create a file input item from base64.
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
		 * Create a function call output item for tool result responses.
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

	public static final class Builder {

		private String baseUrl = io.github.partmeai.openclaw.api.common.OpenClawApiConstants.DEFAULT_BASE_URL;
		private WebClient.Builder webClientBuilder = WebClient.builder();
		private ResponseErrorHandler responseErrorHandler = RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER;
		private SseErrorHandler sseErrorHandler = SseErrorHandler.DEFAULT;

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl cannot be null or empty");
			this.baseUrl = baseUrl;
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

		/**
		 * Configure SSE parse error handling for streaming responses.
		 */
		public Builder sseErrorHandler(SseErrorHandler sseErrorHandler) {
			Assert.notNull(sseErrorHandler, "sseErrorHandler cannot be null");
			this.sseErrorHandler = sseErrorHandler;
			return this;
		}

		public OpenClawResponsesApi build() {
			return new OpenClawResponsesApi(this.baseUrl, this.webClientBuilder,
					this.responseErrorHandler, this.sseErrorHandler);
		}
	}
}

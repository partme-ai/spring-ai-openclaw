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

package io.github.partmeai.openclaw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.partmeai.openclaw.api.OpenClawApi;
import io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest.Tool;
import io.github.partmeai.openclaw.api.OpenClawChatOptions;
import io.github.partmeai.openclaw.api.OpenClawModel;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.FunctionCall;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.InputItems;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.OutputContent;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.OutputItem;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseEvent;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseRequest;
import io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult;
import io.github.partmeai.openclaw.api.OpenClawChatOptions;
import io.github.partmeai.openclaw.api.common.OpenClawApiConstants;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;

/**
 * Model implementation for OpenClaw Gateway's OpenResponses API.
 * <p>
 * Uses the OpenResponses API ({@code POST /v1/responses}) which supports:
 * <ul>
 *   <li>Item-based input (text, images, files)</li>
 *   <li>System instructions</li>
 *   <li>Client-side function tools</li>
 *   <li>Streaming SSE responses</li>
 * </ul>
 * <p>
 * The {@code model} field uses OpenClaw agent-target routing
 * ({@code openclaw/default}, {@code openclaw/<agentId>}).
 *
 * @author Loong Wan
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
@Slf4j
public class OpenClawResponsesModel {

	private final OpenClawResponsesApi responsesApi;

	private final OpenClawChatOptions defaultOptions;

	public OpenClawResponsesModel(OpenClawResponsesApi responsesApi, OpenClawChatOptions defaultOptions) {
		Assert.notNull(responsesApi, "responsesApi must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		this.responsesApi = responsesApi;
		this.defaultOptions = defaultOptions;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Create a simple text response.
	 */
	public ResponseResult call(String text) {
		return call(text, null);
	}

	/**
	 * Create a response with text and options.
	 */
	public ResponseResult call(String text, @Nullable OpenClawChatOptions options) {
		Assert.hasText(text, "text must not be empty");

		OpenClawChatOptions requestOptions = mergeOptions(options);
		Map<String, String> headers = requestOptions.toHttpHeaders();

		ResponseRequest request = ResponseRequest.builder(requestOptions.getModel())
				.input(text)
				.temperature(requestOptions.getTemperature())
				.topP(requestOptions.getTopP())
				.maxOutputTokens(requestOptions.getMaxTokens())
				.user(requestOptions.getUser())
				.build();

		return this.responsesApi.createResponse(request, headers);
	}

	/**
	 * Create a response with multiple input items.
	 */
	public ResponseResult call(List<Object> inputItems, @Nullable OpenClawChatOptions options) {
		Assert.notNull(inputItems, "inputItems must not be null");
		Assert.noNullElements(inputItems.toArray(), "inputItems cannot contain null elements");

		OpenClawChatOptions requestOptions = mergeOptions(options);
		Map<String, String> headers = requestOptions.toHttpHeaders();

		ResponseRequest.Builder builder = ResponseRequest.builder(requestOptions.getModel())
				.input(inputItems)
				.temperature(requestOptions.getTemperature())
				.topP(requestOptions.getTopP())
				.maxOutputTokens(requestOptions.getMaxTokens())
				.user(requestOptions.getUser());

		return this.responsesApi.createResponse(builder.build(), headers);
	}

	/**
	 * Create a streaming response.
	 */
	public Flux<ResponseEvent> stream(String text) {
		return stream(text, null);
	}

	/**
	 * Create a streaming response with options.
	 */
	public Flux<ResponseEvent> stream(String text, @Nullable OpenClawChatOptions options) {
		Assert.hasText(text, "text must not be empty");

		OpenClawChatOptions requestOptions = mergeOptions(options);
		Map<String, String> headers = requestOptions.toHttpHeaders();

		ResponseRequest request = ResponseRequest.builder(requestOptions.getModel())
				.input(text)
				.stream(true)
				.temperature(requestOptions.getTemperature())
				.topP(requestOptions.getTopP())
				.maxOutputTokens(requestOptions.getMaxTokens())
				.user(requestOptions.getUser())
				.build();

		return this.responsesApi.streamingResponse(request, headers);
	}

	/**
	 * Extract text content from a response result.
	 */
	public static String extractText(ResponseResult result) {
		if (result.output() == null) {
			return "";
		}

		StringBuilder text = new StringBuilder();
		for (OutputItem item : result.output()) {
			if ("message".equals(item.type()) && item.content() != null) {
				for (OutputContent content : item.content()) {
					if ("output_text".equals(content.type()) && content.text() != null) {
						text.append(content.text());
					}
				}
			}
			else if ("function_call".equals(item.type()) && item.functionCall() != null) {
				FunctionCall fc = item.functionCall();
				text.append("Function call: ").append(fc.name())
						.append("(").append(fc.arguments()).append(")");
			}
		}
		return text.toString();
	}

	/**
	 * Extract function calls from a response result.
	 */
	public static List<FunctionCallInfo> extractFunctionCalls(ResponseResult result) {
		List<FunctionCallInfo> calls = new ArrayList<>();
		if (result.output() == null) {
			return calls;
		}

		for (OutputItem item : result.output()) {
			if ("function_call".equals(item.type()) && item.functionCall() != null) {
				FunctionCall fc = item.functionCall();
				calls.add(new FunctionCallInfo(fc.id(), fc.name(), fc.arguments()));
			}
		}
		return calls;
	}

	/**
	 * Function call information extracted from a response.
	 */
	public record FunctionCallInfo(String id, String name, String arguments) {}

	/**
	 * Build an input item for a user message.
	 */
	public static List<Object> userMessage(String content) {
		return List.of(InputItems.textMessage("user", content));
	}

	/**
	 * Build an input item for a developer message.
	 */
	public static List<Object> developerMessage(String content) {
		return List.of(InputItems.textMessage("developer", content));
	}

	/**
	 * Build an input item for an image from URL.
	 */
	public static List<Object> imageMessage(String imageUrl) {
		return List.of(InputItems.imageFromUrl(imageUrl));
	}

	/**
	 * Build an input item for a file from URL.
	 */
	public static List<Object> fileMessage(String fileUrl, String mediaType, String filename) {
		return List.of(InputItems.fileFromUrl(fileUrl, mediaType, filename));
	}

	/**
	 * Build a function call output item for returning tool results.
	 */
	public static Map<String, Object> functionOutput(String callId, String output) {
		return InputItems.functionCallOutput(callId, output);
	}

	private OpenClawChatOptions mergeOptions(@Nullable OpenClawChatOptions runtimeOptions) {
		OpenClawChatOptions merged;
		if (runtimeOptions != null) {
			merged = OpenClawChatOptions.fromOptions(runtimeOptions);
		}
		else {
			merged = OpenClawChatOptions.builder().build();
		}
		merged = ModelOptionsUtils.merge(merged, this.defaultOptions, OpenClawChatOptions.class);
		if (!StringUtils.hasText(merged.getModel())) {
			merged.setModel(OpenClawModel.DEFAULT.id());
		}
		return merged;
	}

	public static final class Builder {

		private OpenClawResponsesApi responsesApi;

		private OpenClawChatOptions defaultOptions = OpenClawChatOptions.builder()
				.model(OpenClawModel.DEFAULT.id()).build();

		private Builder() {}

		public Builder responsesApi(OpenClawResponsesApi responsesApi) {
			this.responsesApi = responsesApi;
			return this;
		}

		public Builder defaultOptions(OpenClawChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public OpenClawResponsesModel build() {
			return new OpenClawResponsesModel(this.responsesApi, this.defaultOptions);
		}
	}
}

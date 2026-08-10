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
 * <p>面向 OpenClaw Gateway OpenResponses API 的模型门面。</p>
 *
 * <p>基于 {@code POST /v1/responses} 提供便捷的同步和 SSE 调用，并支持：</p>
 * <ul>
 *   <li>文本、图片和文件条目式输入</li>
 *   <li>系统指令与客户端函数工具</li>
 *   <li>SSE 流式响应</li>
 * </ul>
 *
 * <p>每次调用都会复制并合并运行时选项与默认选项，再将 OpenClaw 专用设置转换为请求头。
 * 模型字段使用 {@code openclaw/default} 或 {@code openclaw/&lt;agentId&gt;} 路由。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
@Slf4j
public class OpenClawResponsesModel {

	/** <p>执行 OpenResponses 请求的 API 客户端。</p> */
	private final OpenClawResponsesApi responsesApi;

	/** <p>与运行时选项合并的默认聊天选项。</p> */
	private final OpenClawChatOptions defaultOptions;

	/**
	 * <p>创建 OpenResponses 模型门面。</p>
	 * @param responsesApi io.github.partmeai.openclaw.api.OpenClawResponsesApi API 客户端
	 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
	 * @throws java.lang.IllegalArgumentException 当任一参数为 {@code null} 时抛出
	 */
	public OpenClawResponsesModel(OpenClawResponsesApi responsesApi, OpenClawChatOptions defaultOptions) {
		Assert.notNull(responsesApi, "responsesApi must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		this.responsesApi = responsesApi;
		this.defaultOptions = defaultOptions;
	}

	/**
	 * <p>创建模型构建器。</p>
	 * @return io.github.partmeai.openclaw.OpenClawResponsesModel.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * <p>使用默认选项创建纯文本响应。</p>
	 * @param text java.lang.String 非空输入文本
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
	 */
	public ResponseResult call(String text) {
		return call(text, null);
	}

	/**
	 * <p>使用文本和可选运行时选项创建响应。</p>
	 * @param text java.lang.String 非空输入文本
	 * @param options io.github.partmeai.openclaw.api.OpenClawChatOptions 运行时选项，可为 {@code null}
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
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
	 * <p>使用多个条目式输入创建响应。</p>
	 * @param inputItems java.util.List&lt;Object&gt; 不包含空元素的输入条目
	 * @param options io.github.partmeai.openclaw.api.OpenClawChatOptions 运行时选项，可为 {@code null}
	 * @return io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
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
	 * <p>使用默认选项创建 SSE 流式响应。</p>
	 * @param text java.lang.String 非空输入文本
	 * @return reactor.core.publisher.Flux&lt;ResponseEvent&gt; 响应事件流
	 */
	public Flux<ResponseEvent> stream(String text) {
		return stream(text, null);
	}

	/**
	 * <p>使用文本和可选运行时选项创建 SSE 流式响应。</p>
	 * @param text java.lang.String 非空输入文本
	 * @param options io.github.partmeai.openclaw.api.OpenClawChatOptions 运行时选项，可为 {@code null}
	 * @return reactor.core.publisher.Flux&lt;ResponseEvent&gt; 响应事件流
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
	 * <p>从响应结果提取可读文本。</p>
	 *
	 * <p>拼接所有 {@code message/output_text} 内容；函数调用条目按
	 * {@code Function call: name(arguments)} 格式追加。</p>
	 * @param result io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
	 * @return java.lang.String 提取后的文本；没有输出时返回空字符串
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
	 * <p>从响应结果提取函数调用。</p>
	 * @param result io.github.partmeai.openclaw.api.OpenClawResponsesApi.ResponseResult 响应结果
	 * @return java.util.List&lt;FunctionCallInfo&gt; 按输出顺序排列的函数调用；没有调用时返回空列表
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
	 * <p>从响应中提取的函数调用信息。</p>
	 * @param id java.lang.String 调用标识
	 * @param name java.lang.String 函数名称
	 * @param arguments java.lang.String JSON 参数文本
	 */
	public record FunctionCallInfo(String id, String name, String arguments) {}

	/**
	 * <p>构造用户文本消息输入。</p>
	 * @param content java.lang.String 消息文本
	 * @return java.util.List&lt;Object&gt; 单元素输入条目列表
	 */
	public static List<Object> userMessage(String content) {
		return List.of(InputItems.textMessage("user", content));
	}

	/**
	 * <p>构造开发者文本消息输入。</p>
	 * @param content java.lang.String 消息文本
	 * @return java.util.List&lt;Object&gt; 单元素输入条目列表
	 */
	public static List<Object> developerMessage(String content) {
		return List.of(InputItems.textMessage("developer", content));
	}

	/**
	 * <p>构造 URL 图片消息输入。</p>
	 * @param imageUrl java.lang.String 图片 URL
	 * @return java.util.List&lt;Object&gt; 单元素输入条目列表
	 */
	public static List<Object> imageMessage(String imageUrl) {
		return List.of(InputItems.imageFromUrl(imageUrl));
	}

	/**
	 * <p>构造 URL 文件消息输入。</p>
	 * @param fileUrl java.lang.String 文件 URL
	 * @param mediaType java.lang.String 媒体类型
	 * @param filename java.lang.String 文件名
	 * @return java.util.List&lt;Object&gt; 单元素输入条目列表
	 */
	public static List<Object> fileMessage(String fileUrl, String mediaType, String filename) {
		return List.of(InputItems.fileFromUrl(fileUrl, mediaType, filename));
	}

	/**
	 * <p>构造用于回传工具结果的函数调用输出。</p>
	 * @param callId java.lang.String 原函数调用标识
	 * @param output java.lang.String 工具输出
	 * @return java.util.Map&lt;String, Object&gt; 函数调用输出条目
	 */
	public static Map<String, Object> functionOutput(String callId, String output) {
		return InputItems.functionCallOutput(callId, output);
	}

	/**
	 * <p>复制并合并运行时选项与默认选项。</p>
	 * @param runtimeOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 运行时选项，可为 {@code null}
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 实际请求选项
	 */
	private OpenClawChatOptions mergeOptions(@Nullable OpenClawChatOptions runtimeOptions) {
		OpenClawChatOptions merged;
		if (runtimeOptions != null) {
			merged = OpenClawChatOptions.fromOptions(runtimeOptions);
		}
		else {
			merged = OpenClawChatOptions.builder().build();
		}
		merged = OpenClawChatOptions.merge(merged, this.defaultOptions);
		if (!StringUtils.hasText(merged.getModel())) {
			merged.setModel(OpenClawModel.DEFAULT.id());
		}
		return merged;
	}

	/** <p>{@link OpenClawResponsesModel} 构建器。</p> */
	public static final class Builder {

		/** <p>OpenResponses API 客户端。</p> */
		private OpenClawResponsesApi responsesApi;

		/** <p>默认聊天选项。</p> */
		private OpenClawChatOptions defaultOptions = OpenClawChatOptions.builder()
				.model(OpenClawModel.DEFAULT.id()).build();

		private Builder() {}

		/**
		 * <p>设置 OpenResponses API 客户端。</p>
		 * @param responsesApi io.github.partmeai.openclaw.api.OpenClawResponsesApi API 客户端
		 * @return io.github.partmeai.openclaw.OpenClawResponsesModel.Builder 当前构建器
		 */
		public Builder responsesApi(OpenClawResponsesApi responsesApi) {
			this.responsesApi = responsesApi;
			return this;
		}

		/**
		 * <p>设置默认聊天选项。</p>
		 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
		 * @return io.github.partmeai.openclaw.OpenClawResponsesModel.Builder 当前构建器
		 */
		public Builder defaultOptions(OpenClawChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * <p>构建 OpenResponses 模型门面。</p>
		 * @return io.github.partmeai.openclaw.OpenClawResponsesModel 新模型实例
		 */
		public OpenClawResponsesModel build() {
			return new OpenClawResponsesModel(this.responsesApi, this.defaultOptions);
		}
	}
}

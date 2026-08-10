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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import io.github.partmeai.openclaw.api.OpenClawApi;
import io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest;
import io.github.partmeai.openclaw.api.OpenClawApi.Message.Role;
import io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCall;
import io.github.partmeai.openclaw.api.OpenClawChatOptions;
import io.github.partmeai.openclaw.api.OpenClawModel;
import io.github.partmeai.openclaw.api.common.OpenClawApiConstants;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * <p>面向 OpenClaw Gateway 的 Spring AI {@link ChatModel} 实现。</p>
 *
 * <p>将 Spring AI 的提示词、聊天选项和工具定义转换为 OpenAI 兼容的
 * {@code /v1/chat/completions} 请求，并把同步、异步及 SSE 响应映射回
 * {@link ChatResponse}。模型字段使用 {@code openclaw/default} 或
 * {@code openclaw/&lt;agentId&gt;} 形式的智能体目标路由；后端提供方模型可通过
 * {@link OpenClawChatOptions#setXOpenclawModel(String)} 对单次请求覆盖。</p>
 *
 * <p>当响应要求执行工具时，本类通过 {@link ToolCallingManager} 执行工具并把工具结果加入
 * 会话历史，随后递归请求模型。响应式路径把阻塞式工具执行切换到 bounded-elastic 调度器，
 * 同时传播 Reactor 上下文和 Micrometer Observation 父子关系。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
public class OpenClawChatModel implements ChatModel {

	/**
	 * <p>默认重试模板，仅执行一次请求，不额外重试。</p>
	 */
	private static final RetryTemplate DEFAULT_RETRY_TEMPLATE = RetryTemplate.builder().maxAttempts(1).build();

	/**
	 * <p>默认聊天模型观测约定。</p>
	 */
	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION =
			new DefaultChatModelObservationConvention();

	/**
	 * <p>未显式配置时使用的工具调用管理器。</p>
	 */
	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER =
			ToolCallingManager.builder().build();

	/**
	 * <p>执行 OpenClaw 聊天 HTTP 请求的 API 客户端。</p>
	 */
	private final OpenClawApi chatApi;

	/**
	 * <p>合并到每次请求中的默认聊天选项。</p>
	 */
	private final OpenClawChatOptions defaultOptions;

	/**
	 * <p>记录聊天模型调用观测数据的注册表。</p>
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * <p>解析工具定义并执行工具调用的管理器。</p>
	 */
	private final ToolCallingManager toolCallingManager;

	/**
	 * <p>判断当前模型响应是否需要执行工具的策略。</p>
	 */
	private final ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate;

	/**
	 * <p>当前实例使用的聊天模型观测约定。</p>
	 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	/**
	 * <p>同步聊天请求使用的重试模板。</p>
	 */
	private final RetryTemplate retryTemplate;

	/**
	 * <p>使用默认工具执行判定策略和默认重试模板创建聊天模型。</p>
	 *
	 * @param openclawApi io.github.partmeai.openclaw.api.OpenClawApi OpenClaw API 客户端
	 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认聊天选项
	 * @param toolCallingManager org.springframework.ai.model.tool.ToolCallingManager 工具调用管理器
	 * @param observationRegistry io.micrometer.observation.ObservationRegistry 观测注册表
	 * @throws java.lang.IllegalArgumentException 当任一参数为 {@code null} 时抛出
	 */
	public OpenClawChatModel(OpenClawApi openclawApi, OpenClawChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry) {
		this(openclawApi, defaultOptions, toolCallingManager, observationRegistry,
				new DefaultToolExecutionEligibilityPredicate(), DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * <p>使用完整依赖创建聊天模型。</p>
	 *
	 * @param openclawApi io.github.partmeai.openclaw.api.OpenClawApi OpenClaw API 客户端
	 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认聊天选项
	 * @param toolCallingManager org.springframework.ai.model.tool.ToolCallingManager 工具调用管理器
	 * @param observationRegistry io.micrometer.observation.ObservationRegistry 观测注册表
	 * @param toolExecutionEligibilityPredicate org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate 工具执行判定策略
	 * @param retryTemplate org.springframework.retry.support.RetryTemplate 同步请求重试模板
	 * @throws java.lang.IllegalArgumentException 当任一参数为 {@code null} 时抛出
	 */
	public OpenClawChatModel(OpenClawApi openclawApi, OpenClawChatOptions defaultOptions,
			ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry,
			ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate,
			RetryTemplate retryTemplate) {

		Assert.notNull(openclawApi, "openclawApi must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(toolExecutionEligibilityPredicate, "toolExecutionEligibilityPredicate must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		this.chatApi = openclawApi;
		this.defaultOptions = defaultOptions;
		this.toolCallingManager = toolCallingManager;
		this.observationRegistry = observationRegistry;
		this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
		this.retryTemplate = retryTemplate;
	}

	/**
	 * <p>创建聊天模型构建器。</p>
	 *
	 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * <p>从 OpenAI 兼容响应构建聊天响应元数据。</p>
	 *
	 * <p>工具调用引发多轮模型请求时，将当前 usage 与上一轮响应 usage 累加。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 当前 API 响应
	 * @param previousChatResponse org.springframework.ai.chat.model.ChatResponse 上一轮聊天响应，可为 {@code null}
	 * @return org.springframework.ai.chat.metadata.ChatResponseMetadata 聚合 usage、模型、结束原因和响应标识后的元数据
	 */
	static ChatResponseMetadata from(OpenClawApi.ChatResponse response, ChatResponse previousChatResponse) {
		Assert.notNull(response, "OpenClawApi.ChatResponse must not be null");

		DefaultUsage newUsage = getDefaultUsage(response);
		Integer promptTokens = newUsage.getPromptTokens();
		Integer generationTokens = newUsage.getCompletionTokens();
		int totalTokens = newUsage.getTotalTokens();

		if (previousChatResponse != null && previousChatResponse.getMetadata() != null
				&& previousChatResponse.getMetadata().getUsage() != null) {
			promptTokens += previousChatResponse.getMetadata().getUsage().getPromptTokens();
			generationTokens += previousChatResponse.getMetadata().getUsage().getCompletionTokens();
			totalTokens += previousChatResponse.getMetadata().getUsage().getTotalTokens();
		}

		DefaultUsage aggregatedUsage = new DefaultUsage(promptTokens, generationTokens, totalTokens);

		String finishReason = null;
		if (response.choices() != null && !response.choices().isEmpty()) {
			finishReason = response.choices().get(0).finishReason();
		}

		return ChatResponseMetadata.builder()
			.usage(aggregatedUsage)
			.model(response.model())
			.keyValue("finish_reason", finishReason)
			.keyValue("id", response.id())
			.keyValue("created", response.created())
			.build();
	}

	/**
	 * <p>读取响应 usage，并把缺失的计数按零处理。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse API 响应
	 * @return org.springframework.ai.chat.metadata.DefaultUsage Spring AI usage 对象
	 */
	private static DefaultUsage getDefaultUsage(OpenClawApi.ChatResponse response) {
		if (response.usage() != null) {
			return new DefaultUsage(
				Optional.ofNullable(response.usage().promptTokens()).orElse(0),
				Optional.ofNullable(response.usage().completionTokens()).orElse(0));
		}
		return new DefaultUsage(0, 0);
	}

	/**
	 * <p>提取 API 响应第一个选项的消息文本。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse API 响应
	 * @return java.lang.String 消息或增量文本；不存在时返回空字符串
	 */
	private static String getResponseContent(OpenClawApi.ChatResponse response) {
		if (response.choices() == null || response.choices().isEmpty()) {
			return "";
		}
		var choice = response.choices().get(0);
		OpenClawApi.Message msg = choice.message() != null ? choice.message() : choice.delta();
		return msg != null && msg.content() != null ? msg.content() : "";
	}

	/**
	 * <p>提取 API 响应第一个选项中的工具调用。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse API 响应
	 * @return java.util.List&lt;OpenClawApi.Message.ToolCall&gt; 工具调用列表；不存在时返回空列表
	 */
	private static List<OpenClawApi.Message.ToolCall> getResponseToolCalls(OpenClawApi.ChatResponse response) {
		if (response.choices() == null || response.choices().isEmpty()) {
			return List.of();
		}
		var choice = response.choices().get(0);
		OpenClawApi.Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) {
			return List.of();
		}
		return msg.toolCalls();
	}

	/**
	 * <p>同步执行一次聊天调用，并在需要时继续执行工具调用轮次。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 原始提示词
	 * @return org.springframework.ai.chat.model.ChatResponse 最终聊天响应
	 */
	@Override
	public ChatResponse call(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalCall(requestPrompt, null);
	}

	/**
	 * <p>异步执行一次聊天调用，并在需要时继续执行工具调用轮次。</p>
	 *
	 * <p>方法返回冷 {@link Mono}；HTTP 请求、Observation 启动和工具执行均在订阅后发生。
	 * 工具执行被调度到 bounded-elastic 线程池，且执行期间恢复订阅方 Reactor 上下文。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 原始提示词
	 * @return reactor.core.publisher.Mono&lt;ChatResponse&gt; 发出最终聊天响应的 Publisher
	 */
	public Mono<ChatResponse> callAsync(Prompt prompt) {
		return this.internalCallAsync(buildRequestPrompt(prompt), null);
	}

	private Mono<ChatResponse> internalCallAsync(Prompt prompt,
			ChatResponse previousChatResponse) {
		// deferContextual 保证每次订阅分别创建请求和 Observation，并读取调用方上下文。
		return Mono.deferContextual(contextView -> {
			OpenClawApi.ChatRequest request = openclawChatRequest(prompt, false);
			Map<String, String> headers = openclawHttpHeaders(prompt);
			ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt).provider(OpenClawApiConstants.PROVIDER_NAME).build();
			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
				this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
				() -> observationContext, this.observationRegistry);
			observation.parentObservation(contextView.getOrDefault(
				ObservationThreadLocalAccessor.KEY, null)).start();

			return this.chatApi.chatAsync(request, headers)
				.map(apiResponse -> toChatResponse(apiResponse, previousChatResponse))
				.doOnNext(observationContext::setResponse)
				.flatMap(response -> {
					if (!this.toolExecutionEligibilityPredicate
							.isToolExecutionRequired(prompt.getOptions(), response)) {
						return Mono.just(response);
					}
					// 工具管理器为同步 API，将其移出响应式事件线程并临时恢复 Reactor 上下文。
					return Mono.fromCallable(() -> {
						try {
							ToolCallReactiveContextHolder.setContext(contextView);
							return this.toolCallingManager.executeToolCalls(prompt, response);
						}
						finally {
							ToolCallReactiveContextHolder.clearContext();
						}
					})
						.subscribeOn(Schedulers.boundedElastic())
						.flatMap(result -> {
							if (result.returnDirect()) {
								return Mono.just(ChatResponse.builder().from(response)
									.generations(ToolExecutionResult.buildGenerations(result)).build());
							}
							// 将工具结果作为新会话历史再次调用模型，并累计上一轮元数据。
							return this.internalCallAsync(new Prompt(result.conversationHistory(),
									prompt.getOptions()), response);
						});
				})
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
		});
	}

	private ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

		OpenClawApi.ChatRequest request = openclawChatRequest(prompt, false);
		Map<String, String> headers = openclawHttpHeaders(prompt);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(OpenClawApiConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
					() -> observationContext, this.observationRegistry)
			.observe(() -> {

				OpenClawApi.ChatResponse openclawResponse =
						this.retryTemplate.execute(ctx -> this.chatApi.chat(request, headers));

				List<AssistantMessage.ToolCall> toolCalls = getResponseToolCalls(openclawResponse)
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
							toolCall.type() != null ? toolCall.type() : "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

				var assistantMessage = AssistantMessage.builder()
					.content(getResponseContent(openclawResponse))
					.properties(Map.of())
					.toolCalls(toolCalls)
					.build();

				String finishReason = null;
				if (openclawResponse.choices() != null && !openclawResponse.choices().isEmpty()) {
					finishReason = openclawResponse.choices().get(0).finishReason();
				}

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
					.finishReason(finishReason)
					.build();

				var generator = new Generation(assistantMessage, generationMetadata);
				ChatResponse chatResponse = new ChatResponse(List.of(generator),
						from(openclawResponse, previousChatResponse));

				observationContext.setResponse(chatResponse);
				return chatResponse;
			});

		// 工具结果不要求直接返回时，递归进入下一轮模型请求。
		if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(prompt.getOptions(), response)) {
			var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
			if (toolExecutionResult.returnDirect()) {
				return ChatResponse.builder()
					.from(response)
					.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
					.build();
			}
			else {
				return this.internalCall(
						new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()), response);
			}
		}

		return response;
	}

	private ChatResponse toChatResponse(OpenClawApi.ChatResponse apiResponse,
			ChatResponse previousChatResponse) {
		List<AssistantMessage.ToolCall> toolCalls = getResponseToolCalls(apiResponse).stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
				toolCall.type() != null ? toolCall.type() : "function",
				toolCall.function().name(), toolCall.function().arguments()))
			.toList();
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(getResponseContent(apiResponse))
			.properties(Map.of())
			.toolCalls(toolCalls)
			.build();
		String finishReason = null;
		if (apiResponse.choices() != null && !apiResponse.choices().isEmpty()) {
			finishReason = apiResponse.choices().get(0).finishReason();
		}
		Generation generation = new Generation(assistantMessage,
			ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation), from(apiResponse, previousChatResponse));
	}

	/**
	 * <p>流式执行聊天调用，并聚合 Spring AI 消息及可能产生的工具调用轮次。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 原始提示词
	 * @return reactor.core.publisher.Flux&lt;ChatResponse&gt; 聊天响应流
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalStream(requestPrompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse) {
		// 每次订阅创建独立 Observation，并把上游 Observation 作为父节点。
		return Flux.deferContextual(contextView -> {
			OpenClawApi.ChatRequest request = openclawChatRequest(prompt, true);
			Map<String, String> headers = openclawHttpHeaders(prompt);

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(OpenClawApiConstants.PROVIDER_NAME)
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION,
					() -> observationContext, this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(
					ObservationThreadLocalAccessor.KEY, null)).start();

			Flux<OpenClawApi.ChatResponse> openclawResponse =
					this.chatApi.streamingChat(request, headers);

			Flux<ChatResponse> chatResponse = openclawResponse.map(chunk -> {
				String content = getResponseContent(chunk);

				List<AssistantMessage.ToolCall> toolCalls = getResponseToolCalls(chunk)
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(),
							toolCall.type() != null ? toolCall.type() : "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

				var assistantMessage = AssistantMessage.builder()
					.content(content)
					.properties(Map.of())
					.toolCalls(toolCalls)
					.build();

				String finishReason = null;
				if (chunk.choices() != null && !chunk.choices().isEmpty()) {
					finishReason = chunk.choices().get(0).finishReason();
				}

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
					.finishReason(finishReason)
					.build();

				var generator = new Generation(assistantMessage, generationMetadata);
				return new ChatResponse(List.of(generator), from(chunk, previousChatResponse));
			});

			// concatMap 保持 SSE 响应顺序，并串行执行可能触发的工具调用轮次。
			Flux<ChatResponse> chatResponseFlux = chatResponse.concatMap(response -> {
				if (this.toolExecutionEligibilityPredicate.isToolExecutionRequired(
						prompt.getOptions(), response)) {
					return Flux.deferContextual(ctx -> {
						ToolExecutionResult toolExecutionResult;
						try {
							ToolCallReactiveContextHolder.setContext(ctx);
							toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
						}
						finally {
							ToolCallReactiveContextHolder.clearContext();
						}
						if (toolExecutionResult.returnDirect()) {
							return Flux.just(ChatResponse.builder().from(response)
								.generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
								.build());
						}
						else {
							return this.internalStream(
								new Prompt(toolExecutionResult.conversationHistory(),
										prompt.getOptions()), response);
						}
					}).subscribeOn(Schedulers.boundedElastic());
				}
				else {
					return Flux.just(response);
				}
			})
			.doOnError(observation::error)
			.doFinally(s -> observation.stop())
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

			return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
		});
	}

	/**
	 * <p>把运行时选项与模型默认选项合并为实际请求提示词。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 原始提示词
	 * @return org.springframework.ai.chat.prompt.Prompt 携带 OpenClaw 选项副本的请求提示词
	 * @throws java.lang.IllegalArgumentException 当合并后缺少模型或工具回调无效时抛出
	 */
	Prompt buildRequestPrompt(Prompt prompt) {
		OpenClawChatOptions runtimeOptions = null;
		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof OpenClawChatOptions ocOpts) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(
						OpenClawChatOptions.fromOptions(ocOpts),
						OpenClawChatOptions.class, OpenClawChatOptions.class);
			}
			else if (prompt.getOptions() instanceof ToolCallingChatOptions tcOpts) {
				runtimeOptions = ModelOptionsUtils.copyToTarget(tcOpts,
						ToolCallingChatOptions.class, OpenClawChatOptions.class);
			}
			else {
				runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(),
						ChatOptions.class, OpenClawChatOptions.class);
			}
		}

		OpenClawChatOptions requestOptions = ModelOptionsUtils.merge(
				runtimeOptions, this.defaultOptions, OpenClawChatOptions.class);

		if (runtimeOptions != null) {
			requestOptions.setInternalToolExecutionEnabled(ModelOptionsUtils.mergeOption(
				runtimeOptions.getInternalToolExecutionEnabled(),
				this.defaultOptions.getInternalToolExecutionEnabled()));
			requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(
				runtimeOptions.getToolNames(), this.defaultOptions.getToolNames()));
			requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(
				runtimeOptions.getToolCallbacks(), this.defaultOptions.getToolCallbacks()));
			requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(
				runtimeOptions.getToolContext(), this.defaultOptions.getToolContext()));
		}
		else {
			requestOptions.setInternalToolExecutionEnabled(
					this.defaultOptions.getInternalToolExecutionEnabled());
			requestOptions.setToolNames(this.defaultOptions.getToolNames());
			requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
			requestOptions.setToolContext(this.defaultOptions.getToolContext());
		}

		if (!StringUtils.hasText(requestOptions.getModel())) {
			throw new IllegalArgumentException("model cannot be null or empty");
		}

		ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());
		return new Prompt(prompt.getInstructions(), requestOptions);
	}

	/**
	 * <p>把 Spring AI 提示词转换为 OpenAI 兼容聊天请求。</p>
	 *
	 * <p>该方法保留包级可见性以供测试。系统、用户、助手和工具消息分别映射到协议角色；
	 * {@code max_completion_tokens} 优先于遗留的 {@code max_tokens}。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 已合并 OpenClaw 选项的提示词
	 * @param stream boolean 是否构建流式请求
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest 协议请求对象
	 * @throws java.lang.IllegalArgumentException 当遇到不支持的消息类型时抛出
	 */
	OpenClawApi.ChatRequest openclawChatRequest(Prompt prompt, boolean stream) {

		List<OpenClawApi.Message> openclawMessages = prompt.getInstructions().stream()
			.flatMap(message -> {
				if (message.getMessageType() == MessageType.SYSTEM) {
					return List.of(OpenClawApi.Message.builder(Role.SYSTEM)
						.content(message.getText()).build()).stream();
				}
				else if (message.getMessageType() == MessageType.USER) {
					var builder = OpenClawApi.Message.builder(Role.USER)
						.content(message.getText());
					return List.of(builder.build()).stream();
				}
				else if (message.getMessageType() == MessageType.ASSISTANT) {
					var assistantMessage = (AssistantMessage) message;
					List<OpenClawApi.Message.ToolCall> toolCalls = null;
					if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
						toolCalls = assistantMessage.getToolCalls().stream()
							.map(toolCall -> {
								var function = new OpenClawApi.Message.ToolCallFunction(
										toolCall.name(), toolCall.arguments());
								return new OpenClawApi.Message.ToolCall(
										toolCall.id(), "function", function);
							}).toList();
					}
					return List.of(OpenClawApi.Message.builder(Role.ASSISTANT)
						.content(assistantMessage.getText())
						.toolCalls(toolCalls)
						.build()).stream();
				}
				else if (message.getMessageType() == MessageType.TOOL) {
					ToolResponseMessage toolMessage = (ToolResponseMessage) message;
					return toolMessage.getResponses().stream()
						.map(tr -> OpenClawApi.Message.builder(Role.TOOL)
							.content(tr.responseData())
							.toolCallId(tr.id())
							.name(tr.name())
							.build());
				}
				throw new IllegalArgumentException("Unsupported message type: "
						+ message.getMessageType());
			}).toList();

		OpenClawChatOptions requestOptions;
		if (prompt.getOptions() instanceof OpenClawChatOptions ocOpts) {
			requestOptions = ocOpts;
		}
		else {
			requestOptions = OpenClawChatOptions.fromOptions(
					(OpenClawChatOptions) prompt.getOptions());
		}

		OpenClawApi.ChatRequest.Builder requestBuilder = OpenClawApi.ChatRequest
			.builder(requestOptions.getModel())
			.stream(stream)
			.messages(openclawMessages)
			.temperature(requestOptions.getTemperature())
			.topP(requestOptions.getTopP())
			.frequencyPenalty(requestOptions.getFrequencyPenalty())
			.presencePenalty(requestOptions.getPresencePenalty())
			.seed(requestOptions.getSeed());

		// 网关同时兼容新旧字段，但新字段具有明确的优先级。
		if (requestOptions.getMaxCompletionTokens() != null) {
			requestBuilder.maxCompletionTokens(requestOptions.getMaxCompletionTokens());
		}
		else if (requestOptions.getMaxTokens() != null) {
			requestBuilder.maxTokens(requestOptions.getMaxTokens());
		}

		if (requestOptions.getStop() != null && !requestOptions.getStop().isEmpty()) {
			requestBuilder.stop(requestOptions.getStop());
		}

		if (requestOptions.getUser() != null) {
			requestBuilder.user(requestOptions.getUser());
		}

		List<ToolDefinition> toolDefinitions = this.toolCallingManager
				.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			requestBuilder.tools(getTools(toolDefinitions));
		}

		return requestBuilder.build();
	}

	/**
	 * <p>从提示词选项提取 {@code x-openclaw-*} 请求头。</p>
	 *
	 * @param prompt org.springframework.ai.chat.prompt.Prompt 请求提示词
	 * @return java.util.Map&lt;String, String&gt; OpenClaw 专用请求头；非 OpenClaw 选项时返回空映射
	 */
	private Map<String, String> openclawHttpHeaders(Prompt prompt) {
		if (prompt.getOptions() instanceof OpenClawChatOptions ocOpts) {
			return ocOpts.toHttpHeaders();
		}
		return Map.of();
	}

	/**
	 * <p>把 Spring AI 工具定义转换为 OpenAI 函数工具。</p>
	 *
	 * @param toolDefinitions java.util.List&lt;ToolDefinition&gt; Spring AI 工具定义
	 * @return java.util.List&lt;ChatRequest.Tool&gt; 协议工具列表
	 */
	private List<ChatRequest.Tool> getTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new ChatRequest.Tool.Function(
					toolDefinition.name(), toolDefinition.description(),
					ModelOptionsUtils.jsonToMap(toolDefinition.inputSchema()));
			return new ChatRequest.Tool(function);
		}).toList();
	}

	/**
	 * <p>获取默认聊天选项的副本。</p>
	 *
	 * @return org.springframework.ai.chat.prompt.ChatOptions 默认选项副本
	 */
	@Override
	public ChatOptions getDefaultOptions() {
		return OpenClawChatOptions.fromOptions(this.defaultOptions);
	}

	/**
	 * <p>设置当前聊天模型使用的观测约定。</p>
	 *
	 * @param observationConvention org.springframework.ai.chat.observation.ChatModelObservationConvention 观测约定
	 * @throws java.lang.IllegalArgumentException 当观测约定为 {@code null} 时抛出
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	/**
	 * <p>{@link OpenClawChatModel} 构建器。</p>
	 *
	 * <p>未设置工具调用管理器时使用类级默认实例；其余字段按构建器默认值或调用方配置传入模型。</p>
	 */
	public static final class Builder {

		/** <p>OpenClaw API 客户端。</p> */
		private OpenClawApi openclawApi;

		/** <p>默认聊天选项。</p> */
		private OpenClawChatOptions defaultOptions = OpenClawChatOptions.builder()
				.model(OpenClawModel.DEFAULT.id()).build();

		/** <p>可选的工具调用管理器。</p> */
		private ToolCallingManager toolCallingManager;

		/** <p>工具执行判定策略。</p> */
		private ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate =
				new DefaultToolExecutionEligibilityPredicate();

		/** <p>观测注册表。</p> */
		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		/** <p>同步请求重试模板。</p> */
		private RetryTemplate retryTemplate = DEFAULT_RETRY_TEMPLATE;

		private Builder() {
		}

		/**
		 * <p>设置 OpenClaw API 客户端。</p>
		 * @param openclawApi io.github.partmeai.openclaw.api.OpenClawApi API 客户端
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder openclawApi(OpenClawApi openclawApi) {
			this.openclawApi = openclawApi;
			return this;
		}

		/**
		 * <p>设置默认聊天选项。</p>
		 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder defaultOptions(OpenClawChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * <p>设置工具调用管理器。</p>
		 * @param toolCallingManager org.springframework.ai.model.tool.ToolCallingManager 工具调用管理器
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		/**
		 * <p>设置工具执行判定策略。</p>
		 * @param toolExecutionEligibilityPredicate org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate 判定策略
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder toolExecutionEligibilityPredicate(
				ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
			this.toolExecutionEligibilityPredicate = toolExecutionEligibilityPredicate;
			return this;
		}

		/**
		 * <p>设置观测注册表。</p>
		 * @param observationRegistry io.micrometer.observation.ObservationRegistry 观测注册表
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		/**
		 * <p>设置同步请求重试模板。</p>
		 * @param retryTemplate org.springframework.retry.support.RetryTemplate 重试模板
		 * @return io.github.partmeai.openclaw.OpenClawChatModel.Builder 当前构建器
		 */
		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		/**
		 * <p>构建 OpenClaw 聊天模型。</p>
		 * @return io.github.partmeai.openclaw.OpenClawChatModel 新聊天模型实例
		 * @throws java.lang.IllegalArgumentException 当必需依赖为空时由模型构造器抛出
		 */
		public OpenClawChatModel build() {
			if (this.toolCallingManager != null) {
				return new OpenClawChatModel(this.openclawApi, this.defaultOptions,
						this.toolCallingManager, this.observationRegistry,
						this.toolExecutionEligibilityPredicate, this.retryTemplate);
			}
			return new OpenClawChatModel(this.openclawApi, this.defaultOptions,
					DEFAULT_TOOL_CALLING_MANAGER, this.observationRegistry,
					this.toolExecutionEligibilityPredicate, this.retryTemplate);
		}
	}
}

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.partmeai.openclaw.api.common.OpenClawApiConstants;
import lombok.Getter;

import lombok.Setter;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.util.JsonHelper;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * <p>OpenClaw Gateway {@code /v1/chat/completions} 端点的强类型聊天选项。</p>
 *
 * <p>模型、采样参数和停止序列等 OpenAI 兼容字段序列化到 JSON 请求体；
 * {@code x-openclaw-model}、{@code x-openclaw-session-key} 等 OpenClaw 专用字段
 * 不进入请求体，而由 {@link #toHttpHeaders()} 转换为 HTTP 请求头。Spring AI 工具调用和
 * 结构化输出字段仅供客户端编排使用。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
@JsonInclude(Include.NON_NULL)
@Getter
public class OpenClawChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	// -----------------------------------------------------------------------
	// Standard OpenAI chat completion fields (JSON body)
	// -----------------------------------------------------------------------

	/** <p>OpenClaw 智能体目标标识，例如 {@code openclaw/default}。</p> */
	@Setter
    @JsonProperty("model")
	private String model;

	/** <p>采样温度，协议常用范围为 0.0 到 2.0。</p> */
	@Setter
    @JsonProperty("temperature")
	private Double temperature;

	/** <p>核采样概率。</p> */
	@Setter
    @JsonProperty("top_p")
	private Double topP;

	/** <p>Top-K 采样参数；OpenClaw 未暴露该字段，因此不发送到 API。</p> */
	@Setter
    @JsonIgnore
	private Integer topK;

	/** <p>频率惩罚，协议常用范围为 -2.0 到 2.0。</p> */
	@Setter
    @JsonProperty("frequency_penalty")
	private Double frequencyPenalty;

	/** <p>存在惩罚，协议常用范围为 -2.0 到 2.0。</p> */
	@Setter
    @JsonProperty("presence_penalty")
	private Double presencePenalty;

	/** <p>用于提高输出可复现性的整数随机种子。</p> */
	@Setter
    @JsonProperty("seed")
	private Integer seed;

	/** <p>停止序列列表。</p> */
	@Setter
    @JsonProperty("stop")
	private List<String> stop;

	/** <p>最大补全 token 数，对应首选字段 {@code max_completion_tokens}。</p> */
	@JsonProperty("max_completion_tokens")
	private Integer maxCompletionTokens;

	/** <p>遗留最大 token 数；与首选字段同时设置时不生效。</p> */
	@JsonProperty("max_tokens")
	private Integer maxTokens;

	/** <p>用于派生稳定会话路由键的 OpenAI 用户标识。</p> */
	@Setter
    @JsonProperty("user")
	private String user;

	// -----------------------------------------------------------------------
	// OpenClaw-specific HTTP header fields (not in JSON body)
	// -----------------------------------------------------------------------

	/** <p>后端提供方/模型覆盖值，通过 {@code x-openclaw-model} 请求头发送。</p> */
	@JsonIgnore
	private String xOpenclawModel;

	/** <p>显式会话路由键，通过 {@code x-openclaw-session-key} 请求头发送。</p> */
	@Setter
    @JsonIgnore
	private String xOpenclawSessionKey;

	/** <p>消息入口通道上下文，通过 {@code x-openclaw-message-channel} 请求头发送。</p> */
	@JsonIgnore
	private String xOpenclawMessageChannel;

	/** <p>兼容性智能体标识覆盖值，通过 {@code x-openclaw-agent-id} 请求头发送。</p> */
	@JsonIgnore
	private String xOpenclawAgentId;

	/**
	 * <p>请求作用域限制，通过 {@code x-openclaw-scopes} 请求头发送。</p>
	 * <p>该限制仅在 trusted-proxy/none 认证模式生效；共享密钥模式会忽略此请求头。</p>
	 */
	@JsonIgnore
	private String xOpenclawScopes;

	// -----------------------------------------------------------------------
	// Spring AI Tool Calling fields (managed by Spring AI, not sent to API)
	// -----------------------------------------------------------------------

	/** <p>是否由 Spring AI 客户端内部执行工具调用。</p> */
	@JsonIgnore
	private Boolean internalToolExecutionEnabled;

	/** <p>运行时可用的工具回调。</p> */
	@JsonIgnore
	private List<ToolCallback> toolCallbacks = new ArrayList<>();

	/** <p>允许启用的工具名称集合。</p> */
	@JsonIgnore
	private Set<String> toolNames = new HashSet<>();

	/** <p>传递给工具执行过程的上下文。</p> */
	@JsonIgnore
	private Map<String, Object> toolContext = new HashMap<>();

	// -----------------------------------------------------------------------
	// Structured output (StructuredOutputChatOptions)
	// -----------------------------------------------------------------------

	/** <p>结构化输出格式或由输出 schema 解析得到的映射。</p> */
	@JsonIgnore
	private Object format;

	// -----------------------------------------------------------------------
	// Factory methods
	// -----------------------------------------------------------------------

	/**
	 * <p>创建聊天选项构建器。</p>
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * <p>复制指定 OpenClaw 聊天选项。</p>
	 *
	 * <p>通过构建器逐字段复制；集合字段沿用各 setter/Builder 的现有复制或合并语义。</p>
	 * @param fromOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 源选项
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 新选项实例
	 */
	public static OpenClawChatOptions fromOptions(OpenClawChatOptions fromOptions) {
		return fromOptions.mutate().build();
	}

	/**
	 * <p>从通用 Spring AI 聊天选项创建 OpenClaw 选项。</p>
	 * @param options org.springframework.ai.chat.prompt.ChatOptions 通用聊天选项
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 映射后的 OpenClaw 选项
	 */
	public static OpenClawChatOptions fromOptions(org.springframework.ai.chat.prompt.ChatOptions options) {
		if (options instanceof OpenClawChatOptions openClawOptions) return fromOptions(openClawOptions);
		Builder builder = builder().model(options.getModel()).temperature(options.getTemperature())
			.topP(options.getTopP()).topK(options.getTopK()).frequencyPenalty(options.getFrequencyPenalty())
			.presencePenalty(options.getPresencePenalty()).stopSequences(options.getStopSequences())
			.maxTokens(options.getMaxTokens());
		if (options instanceof ToolCallingChatOptions toolOptions) {
			builder.toolCallbacks(toolOptions.getToolCallbacks()).toolContext(toolOptions.getToolContext());
		}
		if (options instanceof StructuredOutputChatOptions structuredOptions) {
			builder.outputSchema(structuredOptions.getOutputSchema());
		}
		return builder.build();
	}

	/**
	 * <p>把运行时选项覆盖合并到默认选项副本。</p>
	 *
	 * <p>标量字段采用运行时非空值；工具回调、工具上下文和工具名称使用 Spring AI 的合并语义。</p>
	 * @param runtimeOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 运行时选项，可为 {@code null}
	 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 合并后的新选项
	 */
	public static OpenClawChatOptions merge(OpenClawChatOptions runtimeOptions,
			OpenClawChatOptions defaultOptions) {
		if (runtimeOptions == null) return fromOptions(defaultOptions);
		Builder builder = defaultOptions.mutate();
		if (runtimeOptions.getModel() != null) builder.model(runtimeOptions.getModel());
		if (runtimeOptions.getTemperature() != null) builder.temperature(runtimeOptions.getTemperature());
		if (runtimeOptions.getTopP() != null) builder.topP(runtimeOptions.getTopP());
		if (runtimeOptions.getTopK() != null) builder.topK(runtimeOptions.getTopK());
		if (runtimeOptions.getFrequencyPenalty() != null) builder.frequencyPenalty(runtimeOptions.getFrequencyPenalty());
		if (runtimeOptions.getPresencePenalty() != null) builder.presencePenalty(runtimeOptions.getPresencePenalty());
		if (runtimeOptions.getSeed() != null) builder.seed(runtimeOptions.getSeed());
		if (runtimeOptions.getStop() != null) builder.stop(runtimeOptions.getStop());
		if (runtimeOptions.getMaxCompletionTokens() != null) builder.maxCompletionTokens(runtimeOptions.getMaxCompletionTokens());
		if (runtimeOptions.getMaxTokens() != null) builder.maxTokens(runtimeOptions.getMaxTokens());
		if (runtimeOptions.getUser() != null) builder.user(runtimeOptions.getUser());
		if (runtimeOptions.getXOpenclawModel() != null) builder.xOpenclawModel(runtimeOptions.getXOpenclawModel());
		if (runtimeOptions.getXOpenclawSessionKey() != null) builder.xOpenclawSessionKey(runtimeOptions.getXOpenclawSessionKey());
		if (runtimeOptions.getXOpenclawMessageChannel() != null) builder.xOpenclawMessageChannel(runtimeOptions.getXOpenclawMessageChannel());
		if (runtimeOptions.getXOpenclawAgentId() != null) builder.xOpenclawAgentId(runtimeOptions.getXOpenclawAgentId());
		if (runtimeOptions.getXOpenclawScopes() != null) builder.xOpenclawScopes(runtimeOptions.getXOpenclawScopes());
		if (runtimeOptions.getOutputSchema() != null) builder.outputSchema(runtimeOptions.getOutputSchema());
		builder.internalToolExecutionEnabled(ModelOptionsUtils.mergeOption(runtimeOptions.getInternalToolExecutionEnabled(), defaultOptions.getInternalToolExecutionEnabled()));
		builder.toolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(), defaultOptions.getToolCallbacks()));
		builder.toolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.getToolContext(), defaultOptions.getToolContext()));
		builder.toolNames(runtimeOptions.getToolNames().isEmpty()
			? defaultOptions.getToolNames() : runtimeOptions.getToolNames());
		return builder.build();
	}

	/**
	 * <p>创建包含当前全部字段副本的构建器。</p>
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 可继续修改的构建器
	 */
	@Override
	public Builder mutate() {
		return builder()
			.model(getModel()).temperature(getTemperature()).topP(getTopP()).topK(getTopK())
			.frequencyPenalty(getFrequencyPenalty()).presencePenalty(getPresencePenalty())
			.seed(getSeed()).stop(getStop()).maxCompletionTokens(getMaxCompletionTokens())
			.maxTokens(getMaxTokens()).user(getUser()).xOpenclawModel(getXOpenclawModel())
			.xOpenclawSessionKey(getXOpenclawSessionKey()).xOpenclawMessageChannel(getXOpenclawMessageChannel())
			.xOpenclawAgentId(getXOpenclawAgentId()).xOpenclawScopes(getXOpenclawScopes())
			.outputSchema(getOutputSchema()).internalToolExecutionEnabled(getInternalToolExecutionEnabled())
			.toolCallbacks(new ArrayList<>(getToolCallbacks())).toolNames(new HashSet<>(getToolNames()))
			.toolContext(getToolContext() == null ? null : new HashMap<>(getToolContext()));
	}

	/**
	 * <p>构建 OpenClaw 专用 HTTP 请求头。</p>
	 *
	 * <p>仅加入非空且非空字符串的 {@code x-openclaw-*} 字段。</p>
	 * @return java.util.Map&lt;String, String&gt; 请求头名称和值映射
	 */
	public Map<String, String> toHttpHeaders() {
		Map<String, String> headers = new HashMap<>();
		if (xOpenclawModel != null && !xOpenclawModel.isEmpty()) {
			headers.put(OpenClawApiConstants.HEADER_X_OPENCLAW_MODEL, xOpenclawModel);
		}
		if (xOpenclawSessionKey != null && !xOpenclawSessionKey.isEmpty()) {
			headers.put(OpenClawApiConstants.HEADER_X_OPENCLAW_SESSION_KEY, xOpenclawSessionKey);
		}
		if (xOpenclawMessageChannel != null && !xOpenclawMessageChannel.isEmpty()) {
			headers.put(OpenClawApiConstants.HEADER_X_OPENCLAW_MESSAGE_CHANNEL, xOpenclawMessageChannel);
		}
		if (xOpenclawAgentId != null && !xOpenclawAgentId.isEmpty()) {
			headers.put(OpenClawApiConstants.HEADER_X_OPENCLAW_AGENT_ID, xOpenclawAgentId);
		}
		if (xOpenclawScopes != null && !xOpenclawScopes.isEmpty()) {
			headers.put(OpenClawApiConstants.HEADER_X_OPENCLAW_SCOPES, xOpenclawScopes);
		}
		return headers;
	}

	/**
	 * <p>把当前选项转换为用于 JSON 序列化的键值映射。</p>
	 * @return java.util.Map&lt;String, Object&gt; 选项键值映射
	 */
	public Map<String, Object> toMap() {
		return new JsonHelper().convertToMap(this);
	}

	/**
	 * <p>创建当前选项的副本。</p>
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 选项副本
	 */
	public OpenClawChatOptions copy() {
		return fromOptions(this);
	}

	// -----------------------------------------------------------------------
	// Getters — auto-generated by Lombok @Getter
	//   Note: getStop() and getStopSequences() are bridged below;
	//   getOutputSchema() has custom logic that @Getter can't provide
	// -----------------------------------------------------------------------

	/**
	 * <p>获取停止序列。</p>
	 * @return java.util.List&lt;String&gt; 停止序列列表
	 */
	@Override
	@JsonIgnore
	public List<String> getStopSequences() {
		return getStop();
	}

	/**
	 * <p>获取结构化输出 schema。</p>
	 *
	 * <p>格式已是字符串时直接返回；映射等对象通过 Spring AI 工具序列化为 JSON。</p>
	 * @return java.lang.String 输出 schema JSON；未设置格式时返回 {@code null}
	 */
	@Override
	@JsonIgnore
	public String getOutputSchema() {
		if (this.format == null) {
			return null;
		}
		if (this.format instanceof String str) {
			return str;
		}
		return new JsonHelper().toJson(this.format);
	}

	// -----------------------------------------------------------------------
	// Setters (hand-written to preserve validation logic)
	// -----------------------------------------------------------------------

	/**
	 * <p>设置停止序列。</p>
	 * @param stopSequences java.util.List&lt;String&gt; 停止序列
	 */
	@JsonIgnore
	public void setStopSequences(List<String> stopSequences) {
		setStop(stopSequences);
	}

	/**
	 * <p>设置首选的最大补全 token 数。</p>
	 * @param maxCompletionTokens java.lang.Integer 最大补全 token 数
	 * @see #setMaxTokens(Integer)
	 */
	@JsonIgnore
	public void setMaxCompletionTokens(Integer maxCompletionTokens) {
		this.maxCompletionTokens = maxCompletionTokens;
	}

	/**
	 * <p>设置遗留最大 token 数。</p>
	 * @param maxTokens java.lang.Integer 最大 token 数
	 */
	@JsonIgnore
	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	/**
	 * <p>设置后端提供方/模型覆盖请求头值。</p>
	 * @param xOpenclawModel java.lang.String 后端模型覆盖值
	 */
	public void setXOpenclawModel(String xOpenclawModel) {
		this.xOpenclawModel = xOpenclawModel;
	}

	/**
	 * <p>设置消息入口通道请求头值。</p>
	 * @param xOpenclawMessageChannel java.lang.String 消息通道
	 */
	public void setXOpenclawMessageChannel(String xOpenclawMessageChannel) {
		this.xOpenclawMessageChannel = xOpenclawMessageChannel;
	}

	/**
	 * <p>设置兼容性智能体标识覆盖值。</p>
	 * @param xOpenclawAgentId java.lang.String 智能体标识
	 */
	public void setXOpenclawAgentId(String xOpenclawAgentId) {
		this.xOpenclawAgentId = xOpenclawAgentId;
	}

	/**
	 * <p>设置请求作用域限制。</p>
	 * @param xOpenclawScopes java.lang.String 作用域字符串
	 */
	public void setXOpenclawScopes(String xOpenclawScopes) {
		this.xOpenclawScopes = xOpenclawScopes;
	}

	/**
	 * <p>设置是否由客户端内部执行工具调用。</p>
	 * @param internalToolExecutionEnabled java.lang.Boolean 是否内部执行，可为 {@code null}
	 */
	@Nullable
	@JsonIgnore
	public void setInternalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
		this.internalToolExecutionEnabled = internalToolExecutionEnabled;
	}

	/**
	 * <p>设置工具回调列表，并校验列表及元素均非空。</p>
	 * @param toolCallbacks java.util.List&lt;ToolCallback&gt; 工具回调
	 */
	@JsonIgnore
	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
		Assert.noNullElements(toolCallbacks, "toolCallbacks cannot contain null elements");
		this.toolCallbacks = toolCallbacks;
	}

	/**
	 * <p>设置启用的工具名称，并校验名称包含实际文本。</p>
	 * @param toolNames java.util.Set&lt;String&gt; 工具名称集合
	 */
	@JsonIgnore
	public void setToolNames(Set<String> toolNames) {
		Assert.notNull(toolNames, "toolNames cannot be null");
		Assert.noNullElements(toolNames, "toolNames cannot contain null elements");
		toolNames.forEach(tool -> Assert.hasText(tool, "toolNames cannot contain empty elements"));
		this.toolNames = toolNames;
	}

	/**
	 * <p>设置工具执行上下文。</p>
	 * @param toolContext java.util.Map&lt;String, Object&gt; 工具上下文
	 */
	@JsonIgnore
	public void setToolContext(Map<String, Object> toolContext) {
		this.toolContext = toolContext;
	}

	/**
	 * <p>设置结构化输出 schema。</p>
	 *
	 * <p>非空 JSON 字符串立即解析为映射；空值会清除现有格式。</p>
	 * @param outputSchema java.lang.String 输出 schema JSON，可为 {@code null}
	 */
	@JsonIgnore
	public void setOutputSchema(String outputSchema) {
		if (outputSchema != null) {
			this.format = new JsonHelper().fromJsonToMap(outputSchema);
		}
		else {
			this.format = null;
		}
	}

	// -----------------------------------------------------------------------
	// equals / hashCode
	// -----------------------------------------------------------------------

	/**
	 * <p>按全部请求、请求头、工具和输出字段比较选项。</p>
	 * @param o java.lang.Object 待比较对象
	 * @return boolean 字段全部相等时返回 {@code true}
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		OpenClawChatOptions that = (OpenClawChatOptions) o;
		return Objects.equals(this.model, that.model)
			&& Objects.equals(this.temperature, that.temperature)
			&& Objects.equals(this.topP, that.topP)
			&& Objects.equals(this.topK, that.topK)
			&& Objects.equals(this.frequencyPenalty, that.frequencyPenalty)
			&& Objects.equals(this.presencePenalty, that.presencePenalty)
			&& Objects.equals(this.seed, that.seed)
			&& Objects.equals(this.stop, that.stop)
			&& Objects.equals(this.maxCompletionTokens, that.maxCompletionTokens)
			&& Objects.equals(this.maxTokens, that.maxTokens)
			&& Objects.equals(this.user, that.user)
			&& Objects.equals(this.xOpenclawModel, that.xOpenclawModel)
			&& Objects.equals(this.xOpenclawSessionKey, that.xOpenclawSessionKey)
			&& Objects.equals(this.xOpenclawMessageChannel, that.xOpenclawMessageChannel)
			&& Objects.equals(this.xOpenclawAgentId, that.xOpenclawAgentId)
			&& Objects.equals(this.xOpenclawScopes, that.xOpenclawScopes)
			&& Objects.equals(this.format, that.format)
			&& Objects.equals(this.toolCallbacks, that.toolCallbacks)
			&& Objects.equals(this.toolNames, that.toolNames)
			&& Objects.equals(this.toolContext, that.toolContext)
			&& Objects.equals(this.internalToolExecutionEnabled, that.internalToolExecutionEnabled);
	}

	/**
	 * <p>计算与 {@link #equals(Object)} 一致的哈希值。</p>
	 * @return int 选项哈希值
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.model, this.temperature, this.topP, this.topK,
			this.frequencyPenalty, this.presencePenalty, this.seed, this.stop,
			this.maxCompletionTokens, this.maxTokens,
			this.user, this.xOpenclawModel, this.xOpenclawSessionKey,
			this.xOpenclawMessageChannel, this.xOpenclawAgentId, this.format,
			this.xOpenclawScopes,
			this.toolCallbacks, this.toolNames, this.toolContext,
			this.internalToolExecutionEnabled);
	}

	// -----------------------------------------------------------------------
	// Builder
	// -----------------------------------------------------------------------

	/** <p>{@link OpenClawChatOptions} 构建器。</p> */
	public static final class Builder implements ToolCallingChatOptions.Builder<Builder>,
			StructuredOutputChatOptions.Builder<Builder> {

		/** <p>正在构建的可变选项实例。</p> */
		private final OpenClawChatOptions options = new OpenClawChatOptions();

		/**
		 * <p>设置智能体目标。</p>
		 * @param model java.lang.String 目标标识
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder model(String model) {
			this.options.model = model;
			return this;
		}

		/**
		 * <p>通过枚举设置智能体目标。</p>
		 * @param model io.github.partmeai.openclaw.api.OpenClawModel 目标枚举
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder model(OpenClawModel model) {
			this.options.model = model.getName();
			return this;
		}

		/**
		 * <p>设置采样温度。</p>
		 * @param temperature java.lang.Double 采样温度
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder temperature(Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		/**
		 * <p>设置核采样概率。</p>
		 * @param topP java.lang.Double 核采样概率
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder topP(Double topP) {
			this.options.topP = topP;
			return this;
		}

		/**
		 * <p>设置 Top-K 参数。</p>
		 * @param topK java.lang.Integer Top-K 值
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder topK(Integer topK) {
			this.options.topK = topK;
			return this;
		}

		/**
		 * <p>设置频率惩罚。</p>
		 * @param frequencyPenalty java.lang.Double 频率惩罚
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		/**
		 * <p>设置存在惩罚。</p>
		 * @param presencePenalty java.lang.Double 存在惩罚
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder presencePenalty(Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		/**
		 * <p>设置随机种子。</p>
		 * @param seed java.lang.Integer 随机种子
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder seed(Integer seed) {
			this.options.seed = seed;
			return this;
		}

		/**
		 * <p>设置停止序列。</p>
		 * @param stop java.util.List&lt;String&gt; 停止序列
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder stop(List<String> stop) {
			this.options.stop = stop;
			return this;
		}

		/**
		 * <p>通过 Spring AI 标准名称设置停止序列。</p>
		 * @param stopSequences java.util.List&lt;String&gt; 停止序列
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		@Override
		public Builder stopSequences(List<String> stopSequences) {
			return stop(stopSequences);
		}

		/**
		 * <p>设置首选最大补全 token 数。</p>
		 * @param maxCompletionTokens java.lang.Integer 最大补全 token 数
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder maxCompletionTokens(Integer maxCompletionTokens) {
			this.options.maxCompletionTokens = maxCompletionTokens;
			return this;
		}

		/**
		 * <p>设置遗留最大 token 数。</p>
		 * @param maxTokens java.lang.Integer 最大 token 数
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder maxTokens(Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		/**
		 * <p>设置用户标识。</p>
		 * @param user java.lang.String 用户标识
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder user(String user) {
			this.options.user = user;
			return this;
		}

		/**
		 * <p>设置后端提供方/模型覆盖请求头值。</p>
		 * @param xOpenclawModel java.lang.String 模型覆盖值
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder xOpenclawModel(String xOpenclawModel) {
			this.options.xOpenclawModel = xOpenclawModel;
			return this;
		}

		/**
		 * <p>设置显式会话路由键。</p>
		 * @param xOpenclawSessionKey java.lang.String 会话路由键
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder xOpenclawSessionKey(String xOpenclawSessionKey) {
			this.options.xOpenclawSessionKey = xOpenclawSessionKey;
			return this;
		}

		/**
		 * <p>设置消息入口通道。</p>
		 * @param xOpenclawMessageChannel java.lang.String 消息通道
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder xOpenclawMessageChannel(String xOpenclawMessageChannel) {
			this.options.xOpenclawMessageChannel = xOpenclawMessageChannel;
			return this;
		}

		/**
		 * <p>设置兼容性智能体标识覆盖值。</p>
		 * @param xOpenclawAgentId java.lang.String 智能体标识
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder xOpenclawAgentId(String xOpenclawAgentId) {
			this.options.xOpenclawAgentId = xOpenclawAgentId;
			return this;
		}

		/**
		 * <p>设置请求作用域限制。</p>
		 * @param xOpenclawScopes java.lang.String 作用域字符串
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder xOpenclawScopes(String xOpenclawScopes) {
			this.options.xOpenclawScopes = xOpenclawScopes;
			return this;
		}

		/**
		 * <p>设置结构化输出 schema。</p>
		 * @param outputSchema java.lang.String schema JSON
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder outputSchema(String outputSchema) {
			this.options.setOutputSchema(outputSchema);
			return this;
		}

		/**
		 * <p>设置是否内部执行工具调用。</p>
		 * @param internalToolExecutionEnabled java.lang.Boolean 是否内部执行
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder internalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
			this.options.setInternalToolExecutionEnabled(internalToolExecutionEnabled);
			return this;
		}

		/**
		 * <p>设置工具回调列表。</p>
		 * @param toolCallbacks java.util.List&lt;ToolCallback&gt; 工具回调
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
			this.options.setToolCallbacks(toolCallbacks);
			return this;
		}

		/**
		 * <p>追加工具回调。</p>
		 * @param toolCallbacks org.springframework.ai.tool.ToolCallback[] 工具回调
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder toolCallbacks(ToolCallback... toolCallbacks) {
			Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
			this.options.toolCallbacks.addAll(Arrays.asList(toolCallbacks));
			return this;
		}

		/**
		 * <p>设置工具名称集合。</p>
		 * @param toolNames java.util.Set&lt;String&gt; 工具名称
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder toolNames(Set<String> toolNames) {
			this.options.setToolNames(toolNames);
			return this;
		}

		/**
		 * <p>追加工具名称。</p>
		 * @param toolNames java.lang.String[] 工具名称
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder toolNames(String... toolNames) {
			Assert.notNull(toolNames, "toolNames cannot be null");
			this.options.toolNames.addAll(Set.of(toolNames));
			return this;
		}

		/**
		 * <p>合并工具执行上下文。</p>
		 * @param toolContext java.util.Map&lt;String, Object&gt; 工具上下文
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		public Builder toolContext(Map<String, Object> toolContext) {
			if (this.options.toolContext == null) {
				this.options.toolContext = toolContext;
			}
			else {
				this.options.toolContext.putAll(toolContext);
			}
			return this;
		}

		/**
		 * <p>向工具上下文加入单个键值。</p>
		 * @param key java.lang.String 上下文键
		 * @param value java.lang.Object 上下文值
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		@Override
		public Builder toolContext(String key, Object value) {
			if (this.options.toolContext == null) this.options.toolContext = new HashMap<>();
			this.options.toolContext.put(key, value);
			return this;
		}

		/**
		 * <p>复制当前构建状态。</p>
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 独立构建器副本
		 */
		@Override
		public Builder clone() {
			return OpenClawChatOptions.fromOptions(this.options).mutate();
		}

		/**
		 * <p>把另一个 Spring AI 聊天选项构建器的非空字段合并到当前构建器。</p>
		 * @param other org.springframework.ai.chat.prompt.ChatOptions.Builder&lt;?&gt; 待合并构建器
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions.Builder 当前构建器
		 */
		@Override
		public Builder combineWith(org.springframework.ai.chat.prompt.ChatOptions.Builder<?> other) {
			org.springframework.ai.chat.prompt.ChatOptions otherOptions = other.build();
			if (otherOptions.getModel() != null) model(otherOptions.getModel());
			if (otherOptions.getTemperature() != null) temperature(otherOptions.getTemperature());
			if (otherOptions.getTopP() != null) topP(otherOptions.getTopP());
			if (otherOptions.getTopK() != null) topK(otherOptions.getTopK());
			if (otherOptions.getFrequencyPenalty() != null) frequencyPenalty(otherOptions.getFrequencyPenalty());
			if (otherOptions.getPresencePenalty() != null) presencePenalty(otherOptions.getPresencePenalty());
			if (otherOptions.getStopSequences() != null) stopSequences(otherOptions.getStopSequences());
			if (otherOptions.getMaxTokens() != null) maxTokens(otherOptions.getMaxTokens());
			if (otherOptions instanceof ToolCallingChatOptions toolOptions) {
				toolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(toolOptions.getToolCallbacks(), options.getToolCallbacks()));
				toolContext(ToolCallingChatOptions.mergeToolContext(toolOptions.getToolContext(), options.getToolContext()));
			}
			return this;
		}

		/**
		 * <p>返回已构建的聊天选项实例。</p>
		 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 聊天选项
		 */
		public OpenClawChatOptions build() {
			return this.options;
		}
	}
}

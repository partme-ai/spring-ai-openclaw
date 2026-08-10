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
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Strongly-typed chat options for the OpenClaw Gateway's OpenAI-compatible
 * {@code /v1/chat/completions} endpoint.
 * <p>
 * Standard OpenAI fields (temperature, top_p, frequency_penalty, etc.) are sent
 * in the JSON request body. OpenClaw-specific fields ({@code x-openclaw-model},
 * {@code x-openclaw-session-key}, etc.) are sent as HTTP request headers.
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

	/**
	 * OpenClaw agent target id, e.g. "openclaw/default" or "openclaw/research".
	 */
	@Setter
    @JsonProperty("model")
	private String model;

	/**
	 * Sampling temperature (0.0–2.0).
	 */
	@Setter
    @JsonProperty("temperature")
	private Double temperature;

	/**
	 * Nucleus sampling probability.
	 */
	@Setter
    @JsonProperty("top_p")
	private Double topP;

	/**
	 * Top-k sampling. Not forwarded to the API (OpenClaw doesn't expose top_k).
	 */
	@Setter
    @JsonIgnore
	private Integer topK;

	/**
	 * Frequency penalty (-2.0 to 2.0).
	 */
	@Setter
    @JsonProperty("frequency_penalty")
	private Double frequencyPenalty;

	/**
	 * Presence penalty (-2.0 to 2.0).
	 */
	@Setter
    @JsonProperty("presence_penalty")
	private Double presencePenalty;

	/**
	 * Integer seed for reproducible output.
	 */
	@Setter
    @JsonProperty("seed")
	private Integer seed;

	/**
	 * Stop sequences (string or array of up to 4 strings).
	 */
	@Setter
    @JsonProperty("stop")
	private List<String> stop;

	/**
	 * Maximum completion tokens (maps to max_completion_tokens in the API).
	 * This is the preferred field; max_tokens is the legacy alias.
	 * When both are set, max_completion_tokens takes precedence.
	 */
	@JsonProperty("max_completion_tokens")
	private Integer maxCompletionTokens;

	/**
	 * Maximum tokens (legacy alias for max_completion_tokens).
	 * When both are set, max_completion_tokens takes precedence.
	 */
	@JsonProperty("max_tokens")
	private Integer maxTokens;

	/**
	 * An OpenAI user identifier for stable session routing.
	 * OpenClaw derives a stable session key from this value.
	 */
	@Setter
    @JsonProperty("user")
	private String user;

	// -----------------------------------------------------------------------
	// OpenClaw-specific HTTP header fields (not in JSON body)
	// -----------------------------------------------------------------------

	/**
	 * Override the backend provider/model for the selected agent.
	 * Sent as the {@code x-openclaw-model} HTTP header.
	 * Example: "openai/gpt-5.4" or "gpt-5.5".
	 */
	@JsonIgnore
	private String xOpenclawModel;

	/**
	 * Explicit session routing key.
	 * Sent as the {@code x-openclaw-session-key} HTTP header.
	 */
	@Setter
    @JsonIgnore
	private String xOpenclawSessionKey;

	/**
	 * Synthetic ingress channel context for channel-aware prompts and policies.
	 * Sent as the {@code x-openclaw-message-channel} HTTP header.
	 * Example: "slack", "discord".
	 */
	@JsonIgnore
	private String xOpenclawMessageChannel;

	/**
	 * Compatibility agent-id override.
	 * Sent as the {@code x-openclaw-agent-id} HTTP header.
	 */
	@JsonIgnore
	private String xOpenclawAgentId;

	/**
	 * Scope restriction for the request (honored in trusted-proxy/none auth modes).
	 * Sent as the {@code x-openclaw-scopes} HTTP header.
	 * <p>
	 * In shared-secret modes (token/password), this header is ignored and the
	 * full operator scope set is always used.
	 */
	@JsonIgnore
	private String xOpenclawScopes;

	// -----------------------------------------------------------------------
	// Spring AI Tool Calling fields (managed by Spring AI, not sent to API)
	// -----------------------------------------------------------------------

	@JsonIgnore
	private Boolean internalToolExecutionEnabled;

	@JsonIgnore
	private List<ToolCallback> toolCallbacks = new ArrayList<>();

	@JsonIgnore
	private Set<String> toolNames = new HashSet<>();

	@JsonIgnore
	private Map<String, Object> toolContext = new HashMap<>();

	// -----------------------------------------------------------------------
	// Structured output (StructuredOutputChatOptions)
	// -----------------------------------------------------------------------

	@JsonIgnore
	private Object format;

	// -----------------------------------------------------------------------
	// Factory methods
	// -----------------------------------------------------------------------

	public static Builder builder() {
		return new Builder();
	}

	public static OpenClawChatOptions fromOptions(OpenClawChatOptions fromOptions) {
		return builder()
			.model(fromOptions.getModel())
			.temperature(fromOptions.getTemperature())
			.topP(fromOptions.getTopP())
			.topK(fromOptions.getTopK())
			.frequencyPenalty(fromOptions.getFrequencyPenalty())
			.presencePenalty(fromOptions.getPresencePenalty())
			.seed(fromOptions.getSeed())
			.stop(fromOptions.getStop())
			.maxCompletionTokens(fromOptions.getMaxCompletionTokens())
			.maxTokens(fromOptions.getMaxTokens())
			.user(fromOptions.getUser())
			.xOpenclawModel(fromOptions.getXOpenclawModel())
			.xOpenclawSessionKey(fromOptions.getXOpenclawSessionKey())
			.xOpenclawMessageChannel(fromOptions.getXOpenclawMessageChannel())
			.xOpenclawAgentId(fromOptions.getXOpenclawAgentId())
			.xOpenclawScopes(fromOptions.getXOpenclawScopes())
			.outputSchema(fromOptions.getOutputSchema())
			.internalToolExecutionEnabled(fromOptions.getInternalToolExecutionEnabled())
			.toolCallbacks(fromOptions.getToolCallbacks())
			.toolNames(fromOptions.getToolNames())
			.toolContext(fromOptions.getToolContext())
			.build();
	}

	/**
	 * Build a map of OpenClaw-specific HTTP header values from these options.
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
	 * Convert to a {@link Map} of key/value pairs (for JSON serialization).
	 */
	public Map<String, Object> toMap() {
		return ModelOptionsUtils.objectToMap(this);
	}

	@Override
	public OpenClawChatOptions copy() {
		return fromOptions(this);
	}

	// -----------------------------------------------------------------------
	// Getters — auto-generated by Lombok @Getter
	//   Note: getStop() and getStopSequences() are bridged below;
	//   getOutputSchema() has custom logic that @Getter can't provide
	// -----------------------------------------------------------------------

	@Override
	@JsonIgnore
	public List<String> getStopSequences() {
		return getStop();
	}

	@Override
	@JsonIgnore
	public String getOutputSchema() {
		if (this.format == null) {
			return null;
		}
		if (this.format instanceof String str) {
			return str;
		}
		return ModelOptionsUtils.toJsonString(this.format);
	}

	// -----------------------------------------------------------------------
	// Setters (hand-written to preserve validation logic)
	// -----------------------------------------------------------------------

    @JsonIgnore
	public void setStopSequences(List<String> stopSequences) {
		setStop(stopSequences);
	}

    /**
	 * Set max_completion_tokens (preferred) or max_tokens (legacy).
	 * @see #setMaxTokens(Integer)
	 */
	@JsonIgnore
	public void setMaxCompletionTokens(Integer maxCompletionTokens) {
		this.maxCompletionTokens = maxCompletionTokens;
	}

	@JsonIgnore
	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

    public void setXOpenclawModel(String xOpenclawModel) {
		this.xOpenclawModel = xOpenclawModel;
	}

    public void setXOpenclawMessageChannel(String xOpenclawMessageChannel) {
		this.xOpenclawMessageChannel = xOpenclawMessageChannel;
	}

	public void setXOpenclawAgentId(String xOpenclawAgentId) {
		this.xOpenclawAgentId = xOpenclawAgentId;
	}

	public void setXOpenclawScopes(String xOpenclawScopes) {
		this.xOpenclawScopes = xOpenclawScopes;
	}

	@Nullable
	@JsonIgnore
	public void setInternalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
		this.internalToolExecutionEnabled = internalToolExecutionEnabled;
	}

	@JsonIgnore
	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
		Assert.noNullElements(toolCallbacks, "toolCallbacks cannot contain null elements");
		this.toolCallbacks = toolCallbacks;
	}

	@JsonIgnore
	public void setToolNames(Set<String> toolNames) {
		Assert.notNull(toolNames, "toolNames cannot be null");
		Assert.noNullElements(toolNames, "toolNames cannot contain null elements");
		toolNames.forEach(tool -> Assert.hasText(tool, "toolNames cannot contain empty elements"));
		this.toolNames = toolNames;
	}

	@JsonIgnore
	public void setToolContext(Map<String, Object> toolContext) {
		this.toolContext = toolContext;
	}

	@JsonIgnore
	public void setOutputSchema(String outputSchema) {
		if (outputSchema != null) {
			this.format = ModelOptionsUtils.jsonToMap(outputSchema);
		}
		else {
			this.format = null;
		}
	}

	// -----------------------------------------------------------------------
	// equals / hashCode
	// -----------------------------------------------------------------------

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

	public static final class Builder {

		private final OpenClawChatOptions options = new OpenClawChatOptions();

		public Builder model(String model) {
			this.options.model = model;
			return this;
		}

		public Builder model(OpenClawModel model) {
			this.options.model = model.getName();
			return this;
		}

		public Builder temperature(Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		public Builder topP(Double topP) {
			this.options.topP = topP;
			return this;
		}

		public Builder topK(Integer topK) {
			this.options.topK = topK;
			return this;
		}

		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		public Builder presencePenalty(Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		public Builder seed(Integer seed) {
			this.options.seed = seed;
			return this;
		}

		public Builder stop(List<String> stop) {
			this.options.stop = stop;
			return this;
		}

		/**
		 * Set max_completion_tokens (preferred) or max_tokens (legacy).
		 * When both are set, max_completion_tokens takes precedence.
		 */
		public Builder maxCompletionTokens(Integer maxCompletionTokens) {
			this.options.maxCompletionTokens = maxCompletionTokens;
			return this;
		}

		public Builder maxTokens(Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		public Builder user(String user) {
			this.options.user = user;
			return this;
		}

		/**
		 * Override the backend provider/model for the selected agent.
		 * Sent as the {@code x-openclaw-model} HTTP header.
		 * Example: "openai/gpt-5.4" or "gpt-5.5".
		 */
		public Builder xOpenclawModel(String xOpenclawModel) {
			this.options.xOpenclawModel = xOpenclawModel;
			return this;
		}

		/**
		 * Explicit session routing key.
		 * Sent as the {@code x-openclaw-session-key} HTTP header.
		 */
		public Builder xOpenclawSessionKey(String xOpenclawSessionKey) {
			this.options.xOpenclawSessionKey = xOpenclawSessionKey;
			return this;
		}

		/**
		 * Synthetic ingress channel context (e.g. "slack", "discord").
		 * Sent as the {@code x-openclaw-message-channel} HTTP header.
		 */
		public Builder xOpenclawMessageChannel(String xOpenclawMessageChannel) {
			this.options.xOpenclawMessageChannel = xOpenclawMessageChannel;
			return this;
		}

		/**
		 * Compatibility agent-id override.
		 * Sent as the {@code x-openclaw-agent-id} HTTP header.
		 */
		public Builder xOpenclawAgentId(String xOpenclawAgentId) {
			this.options.xOpenclawAgentId = xOpenclawAgentId;
			return this;
		}

		public Builder xOpenclawScopes(String xOpenclawScopes) {
			this.options.xOpenclawScopes = xOpenclawScopes;
			return this;
		}

		public Builder outputSchema(String outputSchema) {
			this.options.setOutputSchema(outputSchema);
			return this;
		}

		public Builder internalToolExecutionEnabled(@Nullable Boolean internalToolExecutionEnabled) {
			this.options.setInternalToolExecutionEnabled(internalToolExecutionEnabled);
			return this;
		}

		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
			this.options.setToolCallbacks(toolCallbacks);
			return this;
		}

		public Builder toolCallbacks(ToolCallback... toolCallbacks) {
			Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
			this.options.toolCallbacks.addAll(Arrays.asList(toolCallbacks));
			return this;
		}

		public Builder toolNames(Set<String> toolNames) {
			this.options.setToolNames(toolNames);
			return this;
		}

		public Builder toolNames(String... toolNames) {
			Assert.notNull(toolNames, "toolNames cannot be null");
			this.options.toolNames.addAll(Set.of(toolNames));
			return this;
		}

		public Builder toolContext(Map<String, Object> toolContext) {
			if (this.options.toolContext == null) {
				this.options.toolContext = toolContext;
			}
			else {
				this.options.toolContext.putAll(toolContext);
			}
			return this;
		}

		public OpenClawChatOptions build() {
			return this.options;
		}
	}
}

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

package io.github.partmeai.openclaw.api.common;

import org.springframework.ai.observation.conventions.AiProvider;

/**
 * Common value constants for OpenClaw API.
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
public final class OpenClawApiConstants {

	public static final String DEFAULT_BASE_URL = "http://localhost:18789";

	public static final String PROVIDER_NAME = "openclaw";

	// --------------------------------------------------------------------------
	// HTTP Headers (x-openclaw-*)
	// --------------------------------------------------------------------------

	/**
	 * Override the backend provider/model for the selected agent.
	 * Example: "openai/gpt-4o", "gpt-4.5"
	 */
	public static final String HEADER_X_OPENCLAW_MODEL = "x-openclaw-model";

	/**
	 * Explicit session routing key for stable session management.
	 */
	public static final String HEADER_X_OPENCLAW_SESSION_KEY = "x-openclaw-session-key";

	/**
	 * Synthetic ingress channel context for channel-aware prompts and policies.
	 * Example: "slack", "discord", "web"
	 */
	public static final String HEADER_X_OPENCLAW_MESSAGE_CHANNEL = "x-openclaw-message-channel";

	/**
	 * Compatibility agent-id override.
	 */
	public static final String HEADER_X_OPENCLAW_AGENT_ID = "x-openclaw-agent-id";

	/**
	 * Scope restriction for the request (honored in trusted-proxy/none auth modes).
	 */
	public static final String HEADER_X_OPENCLAW_SCOPES = "x-openclaw-scopes";

	// --------------------------------------------------------------------------
	// Agent Target Prefixes
	// --------------------------------------------------------------------------

	/**
	 * Prefix for OpenClaw agent targets.
	 */
	public static final String AGENT_PREFIX_OPENCLAW = "openclaw/";

	/**
	 * Stable alias for the configured default agent.
	 */
	public static final String AGENT_DEFAULT = "openclaw/default";

	/**
	 * Legacy colon-separated prefix (deprecated but supported).
	 */
	public static final String AGENT_PREFIX_OPENCLAW_COLON = "openclaw:";

	/**
	 * Legacy agent: prefix for compatibility.
	 */
	public static final String AGENT_PREFIX_AGENT_COLON = "agent:";

	// --------------------------------------------------------------------------
	// Helper Methods
	// --------------------------------------------------------------------------

	/**
	 * Check if the given value is an agent target (needs routing) rather than
	 * a raw provider model id.
	 */
	public static boolean isAgentTarget(String value) {
		if (value == null) {
			return false;
		}
		return value.startsWith(AGENT_PREFIX_OPENCLAW) ||
			   value.startsWith(AGENT_PREFIX_AGENT_COLON) ||
			   value.startsWith(AGENT_PREFIX_OPENCLAW_COLON);
	}

	private OpenClawApiConstants() {
	}
}

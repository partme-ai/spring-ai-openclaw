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
 * <p>OpenClaw API 通用协议常量与路由判断工具。</p>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openresponses-http-api">OpenResponses API</a>
 */
public final class OpenClawApiConstants {

	/** <p>本地 OpenClaw Gateway 默认地址。</p> */
	public static final String DEFAULT_BASE_URL = "http://localhost:18789";

	/** <p>Spring AI 观测中使用的提供方名称。</p> */
	public static final String PROVIDER_NAME = "openclaw";

	// --------------------------------------------------------------------------
	// HTTP Headers (x-openclaw-*)
	// --------------------------------------------------------------------------

	/** <p>覆盖所选智能体后端提供方/模型的请求头。</p> */
	public static final String HEADER_X_OPENCLAW_MODEL = "x-openclaw-model";

	/** <p>显式会话路由键请求头。</p> */
	public static final String HEADER_X_OPENCLAW_SESSION_KEY = "x-openclaw-session-key";

	/** <p>消息入口通道上下文请求头。</p> */
	public static final String HEADER_X_OPENCLAW_MESSAGE_CHANNEL = "x-openclaw-message-channel";

	/** <p>兼容性智能体标识覆盖请求头。</p> */
	public static final String HEADER_X_OPENCLAW_AGENT_ID = "x-openclaw-agent-id";

	/** <p>受信任代理或无认证模式下的请求作用域限制请求头。</p> */
	public static final String HEADER_X_OPENCLAW_SCOPES = "x-openclaw-scopes";

	// --------------------------------------------------------------------------
	// Agent Target Prefixes
	// --------------------------------------------------------------------------

	/** <p>OpenClaw 智能体目标斜杠前缀。</p> */
	public static final String AGENT_PREFIX_OPENCLAW = "openclaw/";

	/** <p>已配置默认智能体的稳定别名。</p> */
	public static final String AGENT_DEFAULT = "openclaw/default";

	/** <p>仍受支持的遗留 OpenClaw 冒号前缀。</p> */
	public static final String AGENT_PREFIX_OPENCLAW_COLON = "openclaw:";

	/** <p>用于兼容的遗留 agent 冒号前缀。</p> */
	public static final String AGENT_PREFIX_AGENT_COLON = "agent:";

	// --------------------------------------------------------------------------
	// Helper Methods
	// --------------------------------------------------------------------------

	/**
	 * <p>判断值是否为需要智能体路由的目标，而不是原始提供方模型标识。</p>
	 * @param value java.lang.String 待判断标识
	 * @return boolean 匹配任一受支持智能体前缀时返回 {@code true}
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

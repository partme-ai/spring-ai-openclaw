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

import org.springframework.ai.model.ChatModelDescription;

/**
 * Helper class for OpenClaw agent targets.
 * OpenClaw uses agent-target model routing rather than raw provider model IDs.
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#agent-first-model-contract">Agent-first Model Contract</a>
 *
 * @since 1.0.0
 */
public enum OpenClawModel implements ChatModelDescription {

	/**
	 * The default OpenClaw agent.
	 */
	DEFAULT("openclaw/default"),

	/**
	 * Alias for the default agent.
	 */
	OPENCLAW("openclaw");

	private final String id;

	OpenClawModel(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	@Override
	public String getName() {
		return this.id;
	}
}

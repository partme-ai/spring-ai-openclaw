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
 * <p>OpenClaw 预定义智能体目标。</p>
 *
 * <p>OpenClaw 使用智能体目标而非原始提供方模型标识进行路由。</p>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#agent-first-model-contract">Agent-first Model Contract</a>
 *
 * @since 1.0.0
 */
public enum OpenClawModel implements ChatModelDescription {

	/** <p>稳定的默认智能体目标。</p> */
	DEFAULT("openclaw/default"),

	/** <p>默认智能体的短别名。</p> */
	OPENCLAW("openclaw");

	/** <p>协议使用的智能体目标标识。</p> */
	private final String id;

	OpenClawModel(String id) {
		this.id = id;
	}

	/**
	 * <p>获取智能体目标标识。</p>
	 * @return java.lang.String 目标标识
	 */
	public String id() {
		return this.id;
	}

	/**
	 * <p>获取 Spring AI 模型名称。</p>
	 * @return java.lang.String 与目标标识相同的模型名称
	 */
	@Override
	public String getName() {
		return this.id;
	}
}

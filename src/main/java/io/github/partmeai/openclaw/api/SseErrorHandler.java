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

import reactor.core.publisher.Mono;

import io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse;

/**
 * <p>流式聊天补全 SSE 解析错误处理策略。</p>
 *
 * <p>策略返回空 Mono 表示吞掉错误并结束流，返回错误 Mono 表示继续传播。自定义实现可通过
 * {@link OpenClawApi.Builder#sseErrorHandler(SseErrorHandler)} 注册。</p>
 *
 * @see OpenClawApi.Builder#sseErrorHandler(SseErrorHandler)
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#streaming-sse">OpenClaw Streaming SSE</a>
 */
@FunctionalInterface
public interface SseErrorHandler {

	/**
	 * <p>检查 SSE 解析错误并决定抑制或传播。</p>
	 * @param cause java.lang.Throwable SSE 解码器抛出的解析错误
	 * @return reactor.core.publisher.Mono&lt;ChatResponse&gt; 空 Mono 表示抑制，错误 Mono 表示传播
	 */
	Mono<ChatResponse> handle(Throwable cause);

	// -----------------------------------------------------------------------
	// Built-in implementations
	// -----------------------------------------------------------------------

	/**
	 * <p>默认策略：仅抑制同时包含 {@code START_ARRAY} 和 {@code ChatResponse} 的错误消息，
	 * 其他错误原样传播。</p>
	 */
	SseErrorHandler DEFAULT = cause -> {
		String msg = cause.getMessage();
		if (msg != null
				&& msg.contains("START_ARRAY")
				&& msg.contains("ChatResponse")) {
			return Mono.empty();
		}
		return Mono.error(cause);
	};

	/** <p>宽松策略：抑制全部 SSE 解析错误并正常结束流。</p> */
	SseErrorHandler LENIENT = cause -> Mono.empty();

	/** <p>严格策略：传播全部 SSE 解析错误。</p> */
	SseErrorHandler STRICT = Mono::error;
}

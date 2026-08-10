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

import java.util.List;

import io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse;
import io.github.partmeai.openclaw.api.OpenClawApi.Message;

/**
 * <p>OpenAI 兼容聊天补全响应辅助工具。</p>
 *
 * <p>所有方法只读取第一个 choice，并兼容非流式 {@code message} 与流式 {@code delta}。</p>
 *
 * @since 1.0.0
 */
public final class OpenClawApiHelper {

	private OpenClawApiHelper() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	/**
	 * <p>判断流式响应块的第一个增量消息是否包含工具调用。</p>
	 * @param chatResponse io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 响应块
	 * @return boolean 包含至少一个工具调用时返回 {@code true}
	 */
	public static boolean isStreamingToolCall(ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.choices() == null
				|| chatResponse.choices().isEmpty()) {
			return false;
		}
		var delta = chatResponse.choices().get(0).delta();
		return delta != null && delta.toolCalls() != null && !delta.toolCalls().isEmpty();
	}

	/**
	 * <p>判断流式响应块是否携带结束原因。</p>
	 * @param chatResponse io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 响应块
	 * @return boolean 第一个 choice 存在 {@code finish_reason} 时返回 {@code true}
	 */
	public static boolean isStreamingDone(ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.choices() == null
				|| chatResponse.choices().isEmpty()) {
			return false;
		}
		return chatResponse.choices().get(0).finishReason() != null;
	}

	/**
	 * <p>提取第一个 choice 的完整消息或增量消息文本。</p>
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 响应
	 * @return java.lang.String 消息文本；响应结构缺失时返回 {@code null}
	 */
	public static String getContent(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			return null;
		}
		var choice = response.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		return msg != null ? msg.content() : null;
	}

	/**
	 * <p>提取第一个 choice 的完整消息或增量消息工具调用。</p>
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 响应
	 * @return java.util.List&lt;Message.ToolCall&gt; 工具调用；结构缺失时返回空列表
	 */
	public static List<Message.ToolCall> getToolCalls(ChatResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			return List.of();
		}
		var choice = response.choices().get(0);
		Message msg = choice.message() != null ? choice.message() : choice.delta();
		if (msg == null || msg.toolCalls() == null) {
			return List.of();
		}
		return msg.toolCalls();
	}
}

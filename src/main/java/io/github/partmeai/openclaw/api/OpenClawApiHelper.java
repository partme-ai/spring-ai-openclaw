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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse;
import org.springframework.util.CollectionUtils;

/**
 * Helper class for OpenClaw API chat response processing.
 *
 * @since 1.0.0
 */
public final class OpenClawApiHelper {

	private OpenClawApiHelper() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	public static boolean isStreamingToolCall(OpenClawApi.ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.message() == null
				|| chatResponse.message().toolCalls() == null) {
			return false;
		}
		return !CollectionUtils.isEmpty(chatResponse.message().toolCalls());
	}

	public static boolean isStreamingDone(OpenClawApi.ChatResponse chatResponse) {
		if (chatResponse == null) {
			return false;
		}
		return chatResponse.done() != null && chatResponse.done() && "stop".equals(chatResponse.doneReason());
	}

	public static ChatResponse merge(ChatResponse previous, ChatResponse current) {
		String model = merge(previous.model(), current.model());
		Instant createdAt = merge(previous.createdAt(), current.createdAt());
		OpenClawApi.Message message = merge(previous.message(), current.message());
		String doneReason = (current.doneReason() != null ? current.doneReason() : previous.doneReason());
		Boolean done = (current.done() != null ? current.done() : previous.done());
		Long totalDuration = merge(previous.totalDuration(), current.totalDuration());
		Long loadDuration = merge(previous.loadDuration(), current.loadDuration());
		Integer promptEvalCount = merge(previous.promptEvalCount(), current.promptEvalCount());
		Long promptEvalDuration = merge(previous.promptEvalDuration(), current.promptEvalDuration());
		Integer evalCount = merge(previous.evalCount(), current.evalCount());
		Long evalDuration = merge(previous.evalDuration(), current.evalDuration());

		return new ChatResponse(model, createdAt, message, doneReason, done, totalDuration, loadDuration,
				promptEvalCount, promptEvalDuration, evalCount, evalDuration, null, null, null, null);
	}

	private static OpenClawApi.Message merge(OpenClawApi.Message previous, OpenClawApi.Message current) {
		String content = mergeContent(previous, current);
		OpenClawApi.Message.Role role = (current.role() != null ? current.role() : previous.role());
		role = (role != null ? role : OpenClawApi.Message.Role.ASSISTANT);
		List<String> images = mergeImages(previous, current);
		List<OpenClawApi.Message.ToolCall> toolCalls = mergeToolCall(previous, current);

		return OpenClawApi.Message.builder(role)
			.content(content)
			.images(images)
			.toolCalls(toolCalls)
			.build();
	}

	private static Instant merge(Instant previous, Instant current) {
		return (current != null ? current : previous);
	}

	private static Integer merge(Integer previous, Integer current) {
		if (previous == null) {
			return current;
		}
		if (current == null) {
			return previous;
		}
		return previous + current;
	}

	private static Long merge(Long previous, Long current) {
		if (previous == null) {
			return current;
		}
		if (current == null) {
			return previous;
		}
		return previous + current;
	}

	private static String merge(String previous, String current) {
		if (previous == null) {
			return current;
		}
		if (current == null) {
			return previous;
		}
		return previous + current;
	}

	private static String mergeContent(OpenClawApi.Message previous, OpenClawApi.Message current) {
		if (previous == null || previous.content() == null) {
			return (current != null ? current.content() : null);
		}
		if (current == null || current.content() == null) {
			return (previous != null ? previous.content() : null);
		}
		return previous.content() + current.content();
	}

	private static List<OpenClawApi.Message.ToolCall> mergeToolCall(OpenClawApi.Message previous,
			OpenClawApi.Message current) {
		if (previous == null) {
			return (current != null ? current.toolCalls() : null);
		}
		if (current == null) {
			return previous.toolCalls();
		}
		return merge(previous.toolCalls(), current.toolCalls());
	}

	private static List<String> mergeImages(OpenClawApi.Message previous, OpenClawApi.Message current) {
		if (previous == null) {
			return (current != null ? current.images() : null);
		}
		if (current == null) {
			return previous.images();
		}
		return merge(previous.images(), current.images());
	}

	private static <T> List<T> merge(List<T> previous, List<T> current) {
		if (previous == null) {
			return current;
		}
		if (current == null) {
			return previous;
		}
		List<T> merged = new ArrayList<>(previous);
		merged.addAll(current);
		return merged;
	}
}

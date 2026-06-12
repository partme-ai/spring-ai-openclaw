package io.github.partmeai.openclaw.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenClawApiHelper}.
 */
public class OpenClawApiHelperTests {

	@Test
	void isStreamingToolCallWhenToolCallsInDeltaShouldReturnTrue() {
		var toolCall = new OpenClawApi.Message.ToolCall("call-1", "function",
				new OpenClawApi.Message.ToolCallFunction("test_fn", "{}"));
		var delta = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.toolCalls(List.of(toolCall)).build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, null, delta, null);
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.isStreamingToolCall(response)).isTrue();
	}

	@Test
	void isStreamingToolCallWhenNoToolCallsShouldReturnFalse() {
		var delta = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.content("hello").build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, null, delta, null);
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.isStreamingToolCall(response)).isFalse();
	}

	@Test
	void isStreamingToolCallWhenResponseIsNullShouldReturnFalse() {
		assertThat(OpenClawApiHelper.isStreamingToolCall(null)).isFalse();
	}

	@Test
	void isStreamingDoneWhenHasFinishReasonShouldReturnTrue() {
		var delta = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT).build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, null, delta, "stop");
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.isStreamingDone(response)).isTrue();
	}

	@Test
	void isStreamingDoneWhenNoFinishReasonShouldReturnFalse() {
		var delta = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.content("hello").build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, null, delta, null);
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.isStreamingDone(response)).isFalse();
	}

	@Test
	void isStreamingDoneWhenResponseIsNullShouldReturnFalse() {
		assertThat(OpenClawApiHelper.isStreamingDone(null)).isFalse();
	}

	@Test
	void getContentFromMessage() {
		var msg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.content("Hello World").build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, msg, null, "stop");
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.getContent(response)).isEqualTo("Hello World");
	}

	@Test
	void getContentFromDelta() {
		var delta = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.content("Hello").build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, null, delta, null);
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion.chunk",
				null, "openclaw/default", List.of(choice), null);
		assertThat(OpenClawApiHelper.getContent(response)).isEqualTo("Hello");
	}

	@Test
	void getToolCallsFromMessage() {
		var toolCall = new OpenClawApi.Message.ToolCall("call-1", "function",
				new OpenClawApi.Message.ToolCallFunction("test_fn", "{\"arg\":1}"));
		var msg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.toolCalls(List.of(toolCall)).build();
		var choice = new OpenClawApi.ChatResponse.Choice(0, msg, null, "tool_calls");
		var response = new OpenClawApi.ChatResponse("chatcmpl-1", "chat.completion",
				null, "openclaw/default", List.of(choice), null);
		var toolCalls = OpenClawApiHelper.getToolCalls(response);
		assertThat(toolCalls).hasSize(1);
		assertThat(toolCalls.get(0).function().name()).isEqualTo("test_fn");
	}
}

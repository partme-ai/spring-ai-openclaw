package io.github.partmeai.openclaw.api;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OpenClawApiHelperTests {

	@Test
	void isStreamingToolCallWhenToolCallsIsNullShouldReturnFalse() {
		var response = new OpenClawApi.ChatResponse(null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null);
		assertThat(OpenClawApiHelper.isStreamingToolCall(response)).isFalse();
	}

	@Test
	void isStreamingDoneWhenDoneIsTrueAndDoneReasonIsStopShouldReturnTrue() {
		var response = new OpenClawApi.ChatResponse(null, null, null, "stop", true, null, null, null, null, null, null,
				null, null, null, null);
		assertThat(OpenClawApiHelper.isStreamingDone(response)).isTrue();
	}

	@Test
	void isStreamingDoneWhenResponseIsNullShouldReturnFalse() {
		assertThat(OpenClawApiHelper.isStreamingDone(null)).isFalse();
	}

	@Test
	void mergeWhenBothHaveValues() {
		var prevMsg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT).content("Hello").build();
		var prev = new OpenClawApi.ChatResponse("m1", null, prevMsg, null, null, 100L, 50L, 10, null, 5, 100L,
				null, null, null, null);
		var curMsg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT).content("World").build();
		var cur = new OpenClawApi.ChatResponse("m2", null, curMsg, null, null, 200L, 100L, 20, null, 10, 200L,
				null, null, null, null);

		var result = OpenClawApiHelper.merge(prev, cur);
		assertThat(result.model()).isEqualTo("m1m2");
		assertThat(result.message().content()).isEqualTo("HelloWorld");
		assertThat(result.totalDuration()).isEqualTo(300L);
		assertThat(result.evalCount()).isEqualTo(15);
	}

	@Test
	void mergeListsCombines() {
		var prevMsg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.images(Arrays.asList("a", "b")).build();
		var prev = new OpenClawApi.ChatResponse(null, null, prevMsg, null, null, null, null, null, null, null, null,
				null, null, null, null);
		var curMsg = OpenClawApi.Message.builder(OpenClawApi.Message.Role.ASSISTANT)
				.images(List.of("c")).build();
		var cur = new OpenClawApi.ChatResponse(null, null, curMsg, null, null, null, null, null, null, null, null,
				null, null, null, null);

		var result = OpenClawApiHelper.merge(prev, cur);
		assertThat(result.message().images()).containsExactly("a", "b", "c");
	}
}

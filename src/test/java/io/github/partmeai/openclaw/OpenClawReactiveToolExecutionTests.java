package io.github.partmeai.openclaw;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.partmeai.openclaw.api.OpenClawApi;
import io.github.partmeai.openclaw.api.OpenClawChatOptions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class OpenClawReactiveToolExecutionTests {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws Exception {
		this.server = new MockWebServer();
		this.server.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		this.server.shutdown();
	}

	@Test
	void shouldExecuteFragmentedStreamingToolCallExactlyOnce() {
		this.server.enqueue(new MockResponse()
			.setHeader("Content-Type", "text/event-stream")
			.setBody(toolCallStream()));
		AtomicInteger executions = new AtomicInteger();
		AtomicReference<ChatResponse> executedResponse = new AtomicReference<>();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "sunny")))
			.build();
		ToolExecutionResult toolExecutionResult = ToolExecutionResult.builder()
			.conversationHistory(List.of(toolResponse))
			.returnDirect(true)
			.build();
		ToolCallingManager toolCallingManager = new ToolCallingManager() {
			@Override
			public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
				return List.of();
			}

			@Override
			public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
				executions.incrementAndGet();
				executedResponse.set(response);
				return toolExecutionResult;
			}
		};
		ToolExecutionEligibilityPredicate predicate = (options, response) ->
				!response.getResult().getOutput().getToolCalls().isEmpty();

		OpenClawApi api = OpenClawApi.builder().baseUrl(this.server.url("/").toString()).build();
		OpenClawChatModel model = OpenClawChatModel.builder()
			.openclawApi(api)
			.defaultOptions(OpenClawChatOptions.builder().model("openclaw/default")
				.internalToolExecutionEnabled(true).build())
			.toolCallingManager(toolCallingManager)
			.toolExecutionEligibilityPredicate(predicate)
			.build();

		List<ChatResponse> responses = model.stream(new Prompt("weather"))
			.collectList().block(Duration.ofSeconds(10));

		assertThat(responses).isNotEmpty();
		assertThat(executions).hasValue(1);
		var toolCall = executedResponse.get().getResult().getOutput().getToolCalls().get(0);
		assertThat(toolCall.id()).isEqualTo("call-1");
		assertThat(toolCall.name()).isEqualTo("weather");
		assertThat(toolCall.arguments()).isEqualTo("{\"city\":\"Paris\"}");
	}

	private String toolCallStream() {
		OpenClawApi.ChatResponse first = chunk(new OpenClawApi.Message.ToolCall(0, "call-1", "function",
				new OpenClawApi.Message.ToolCallFunction("weather", "{\"city\":\"")), null);
		OpenClawApi.ChatResponse second = chunk(new OpenClawApi.Message.ToolCall(0, null, null,
				new OpenClawApi.Message.ToolCallFunction(null, "Paris\"}")), null);
		OpenClawApi.ChatResponse terminal = chunk(null, "tool_calls");
		return "data: " + ModelOptionsUtils.toJsonString(first) + "\n\n"
				+ "data: " + ModelOptionsUtils.toJsonString(second) + "\n\n"
				+ "data: " + ModelOptionsUtils.toJsonString(terminal) + "\n\n"
				+ "data: [DONE]\n\n";
	}

	private OpenClawApi.ChatResponse chunk(OpenClawApi.Message.ToolCall toolCall,
			String finishReason) {
		List<OpenClawApi.Message.ToolCall> toolCalls = toolCall == null ? null : List.of(toolCall);
		OpenClawApi.Message delta = new OpenClawApi.Message(OpenClawApi.Message.Role.ASSISTANT,
				null, toolCalls, null, null);
		OpenClawApi.ChatResponse.Choice choice = new OpenClawApi.ChatResponse.Choice(
				0, null, delta, finishReason);
		return new OpenClawApi.ChatResponse("chat-1", "chat.completion.chunk", 1L,
				"openclaw/default", List.of(choice), null);
	}
}

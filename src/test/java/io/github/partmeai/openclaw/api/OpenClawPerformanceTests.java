package io.github.partmeai.openclaw.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Flux;

import io.github.partmeai.openclaw.OpenClawChatModel;
import io.github.partmeai.openclaw.api.OpenClawApi.ChatRequest;
import io.github.partmeai.openclaw.api.OpenClawApi.Message;
import io.github.partmeai.openclaw.api.OpenClawApi.Message.Role;
import io.github.partmeai.openclaw.management.OpenClawModelManager;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenClawPerformanceTests {

	private static final int CONCURRENCY = 300;
	private static final String CHAT_RESPONSE = """
			{"id":"chat-1","object":"chat.completion","created":1,"model":"openclaw/default",
			 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
			""";

	private MockWebServer server;
	private OpenClawApi api;
	private final AtomicReference<Throwable> unexpectedDroppedError = new AtomicReference<>();
	private Level previousMockWebServerLogLevel;

	@BeforeEach
	void setUp() throws Exception {
		Logger mockWebServerLogger = Logger.getLogger(MockWebServer.class.getName());
		this.previousMockWebServerLogLevel = mockWebServerLogger.getLevel();
		mockWebServerLogger.setLevel(Level.OFF);
		this.server = new MockWebServer();
		this.server.start();
		this.api = OpenClawApi.builder().baseUrl(this.server.url("/").toString()).build();
		Hooks.onErrorDropped(error -> {
			Throwable cause = Exceptions.unwrap(error);
			if (!isCancellation(cause)) {
				this.unexpectedDroppedError.compareAndSet(null, cause);
			}
		});
	}

	@AfterEach
	void tearDown() throws Exception {
		Hooks.resetOnErrorDropped();
		this.server.shutdown();
		Logger.getLogger(MockWebServer.class.getName()).setLevel(this.previousMockWebServerLogLevel);
		assertThat(this.unexpectedDroppedError.get()).isNull();
	}

	@Test
	void shouldCompleteThreeHundredConcurrentReactiveModelRequests() {
		for (int i = 0; i < CONCURRENCY; i++) {
			this.server.enqueue(jsonResponse(CHAT_RESPONSE));
		}
		OpenClawChatModel chatModel = OpenClawChatModel.builder()
			.openclawApi(this.api)
			.defaultOptions(OpenClawChatOptions.builder().model("openclaw/default").build())
			.build();

		List<ChatResponse> responses = Flux.range(0, CONCURRENCY)
				.flatMap(index -> chatModel.callAsync(new Prompt("hello")), CONCURRENCY)
				.collectList()
				.block(Duration.ofSeconds(30));

		assertThat(responses).hasSize(CONCURRENCY);
		assertThat(this.server.getRequestCount()).isEqualTo(CONCURRENCY);
	}

	@Test
	void shouldCancelThreeHundredSseSubscriptionsWithoutRetainingState() {
		for (int i = 0; i < CONCURRENCY; i++) {
			this.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		}

		List<Disposable> subscriptions = new ArrayList<>(CONCURRENCY);
		for (int i = 0; i < CONCURRENCY; i++) {
			subscriptions.add(this.api.streamingChat(chatRequest(true)).subscribe());
		}

		Integer observedRequestCount = Flux.interval(Duration.ofMillis(10))
				.map(tick -> this.server.getRequestCount())
				.filter(count -> count == CONCURRENCY)
				.next()
				.block(Duration.ofSeconds(30));
		assertThat(observedRequestCount).isEqualTo(CONCURRENCY);
		assertThat(this.api.getActiveStreamCount()).isEqualTo(CONCURRENCY);

		subscriptions.forEach(Disposable::dispose);
		assertThat(this.api.getActiveStreamCount()).isZero();
	}

	@Test
	void shouldNotRetryFailedPostByDefault() {
		this.server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));

		assertThatThrownBy(() -> this.api.chatAsync(chatRequest(false)).block(Duration.ofSeconds(5)))
				.isInstanceOf(RuntimeException.class);
		assertThat(this.server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void shouldRejectAboveConfiguredConcurrencyWithoutQueueing() {
		OpenClawApi limitedApi = OpenClawApi.builder()
			.baseUrl(this.server.url("/").toString())
			.maxConcurrentRequests(2)
			.build();
		for (int i = 0; i < 2; i++) {
			this.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		}

		Disposable first = limitedApi.chatAsync(chatRequest(false)).subscribe();
		Disposable second = limitedApi.chatAsync(chatRequest(false)).subscribe();
		waitForRequestCount(2);
		assertThat(limitedApi.getInFlightRequestCount()).isEqualTo(2);

		assertThatThrownBy(() -> limitedApi.chatAsync(chatRequest(false)).block(Duration.ofSeconds(1)))
			.isInstanceOf(RejectedExecutionException.class);
		assertThat(this.server.getRequestCount()).isEqualTo(2);

		first.dispose();
		second.dispose();
		waitForInFlightCount(limitedApi, 0);
	}

	@Test
	void shouldCoalesceThreeHundredConcurrentModelRefreshes() {
		this.server.enqueue(jsonResponse("""
				{"object":"list","data":[{"id":"openclaw/default","object":"model"}]}
				""").setBodyDelay(200, TimeUnit.MILLISECONDS));
		OpenClawModelManager manager = new OpenClawModelManager(this.api);

		List<List<String>> results = Flux.range(0, CONCURRENCY)
			.flatMap(index -> manager.listAgentTargetsAsync(), CONCURRENCY)
			.collectList()
			.block(Duration.ofSeconds(10));

		assertThat(results).hasSize(CONCURRENCY).allSatisfy(models ->
				assertThat(models).containsExactly("openclaw/default"));
		assertThat(this.server.getRequestCount()).isEqualTo(1);
	}

	private ChatRequest chatRequest(boolean stream) {
		Message message = Message.builder(Role.USER).content("hello").build();
		return ChatRequest.builder("openclaw/default").messages(List.of(message)).stream(stream).build();
	}

	private MockResponse jsonResponse(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}

	private void waitForRequestCount(int expected) {
		Integer observed = Flux.interval(Duration.ofMillis(10))
			.map(tick -> this.server.getRequestCount())
			.filter(count -> count == expected)
			.next()
			.block(Duration.ofSeconds(10));
		assertThat(observed).isEqualTo(expected);
	}

	private void waitForInFlightCount(OpenClawApi targetApi, int expected) {
		Integer observed = Flux.interval(Duration.ofMillis(10))
			.map(tick -> targetApi.getInFlightRequestCount())
			.filter(count -> count == expected)
			.next()
			.block(Duration.ofSeconds(10));
		assertThat(observed).isEqualTo(expected);
	}

	private boolean isCancellation(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof CancellationException) {
				return true;
			}
		}
		return false;
	}
}

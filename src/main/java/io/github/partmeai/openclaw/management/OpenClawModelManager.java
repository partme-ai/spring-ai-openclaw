package io.github.partmeai.openclaw.management;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.github.partmeai.openclaw.api.OpenClawApi;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import org.springframework.util.Assert;

/**
 * Manages OpenClaw agent-target discovery through {@code GET /v1/models}.
 * <p>
 * Model listings are cached briefly and concurrent reactive refreshes share one
 * upstream request. This avoids duplicate discovery traffic during concurrent
 * application startup and availability checks.
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#model-list-and-agent-routing">Model list and agent routing</a>
 */
@Slf4j
public class OpenClawModelManager {

	public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

	private final OpenClawApi api;
	private final long cacheTtlNanos;
	private final Object cacheMonitor = new Object();
	private final AtomicReference<Mono<List<String>>> refreshInFlight = new AtomicReference<>();

	private volatile List<String> cachedAgentTargets = List.of();
	private volatile long cacheExpiresAtNanos;

	public OpenClawModelManager(OpenClawApi api) {
		this(api, DEFAULT_CACHE_TTL);
	}

	public OpenClawModelManager(OpenClawApi api, Duration cacheTtl) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(cacheTtl, "cacheTtl must not be null");
		Assert.isTrue(!cacheTtl.isNegative(), "cacheTtl must not be negative");
		this.api = api;
		this.cacheTtlNanos = cacheTtl.toNanos();
	}

	/**
	 * Lists all available agent targets, refreshing an expired cache at most once
	 * among concurrent synchronous callers.
	 *
	 * @return immutable agent-target identifiers, or the last cached list on failure
	 */
	public List<String> listAgentTargets() {
		List<String> cached = currentCache();
		if (Objects.nonNull(cached)) {
			log.trace("Using cached OpenClaw agent-target list: size={}", cached.size());
			return cached;
		}
		synchronized (this.cacheMonitor) {
			cached = currentCache();
			if (Objects.nonNull(cached)) {
				return cached;
			}
			try {
				log.debug("Refreshing OpenClaw agent-target list synchronously");
				List<String> targets = toAgentTargetIds(this.api.listModels());
				updateCache(targets);
				return targets;
			}
			catch (Exception ex) {
				log.warn("Failed to list agent targets from OpenClaw Gateway: {}", ex.getMessage());
				return this.cachedAgentTargets;
			}
		}
	}

	/**
	 * Lists agent targets without blocking and coalesces concurrent cache misses
	 * into one upstream request.
	 *
	 * @return a publisher yielding the immutable agent-target identifier list
	 */
	public Mono<List<String>> listAgentTargetsAsync() {
		List<String> cached = currentCache();
		if (Objects.nonNull(cached)) {
			log.trace("Using cached OpenClaw agent-target list asynchronously: size={}", cached.size());
			return Mono.just(cached);
		}

		Mono<List<String>> activeRefresh = this.refreshInFlight.get();
		if (Objects.nonNull(activeRefresh)) {
			log.trace("Joining in-flight OpenClaw agent-target refresh");
			return activeRefresh;
		}

		Mono<List<String>> refresh = this.api.listModelsAsync()
			.doOnSubscribe(subscription -> log.debug("Refreshing OpenClaw agent-target list asynchronously"))
			.map(OpenClawModelManager::toAgentTargetIds)
			.doOnNext(this::updateCache)
			.onErrorResume(ex -> {
				log.warn("Failed to list agent targets asynchronously from OpenClaw Gateway: {}",
						ex.getMessage());
				return Mono.just(this.cachedAgentTargets);
			})
			.doFinally(signal -> this.refreshInFlight.set(null))
			.cache();
		if (this.refreshInFlight.compareAndSet(null, refresh)) {
			return refresh;
		}
		return this.refreshInFlight.get();
	}

	public Optional<OpenClawApi.ModelResponse> getAgentTarget(String modelId) {
		try {
			return Optional.ofNullable(this.api.getModel(modelId));
		}
		catch (Exception ex) {
			log.warn("Failed to get agent target '{}': {}", modelId, ex.getMessage());
			return Optional.empty();
		}
	}

	public boolean isAgentTargetAvailable(String modelId) {
		return listAgentTargets().contains(modelId);
	}

	public String getDefaultAgentTarget() {
		return "openclaw/default";
	}

	public void invalidateCache() {
		this.cacheExpiresAtNanos = 0;
	}

	private List<String> currentCache() {
		return System.nanoTime() < this.cacheExpiresAtNanos ? this.cachedAgentTargets : null;
	}

	private void updateCache(List<String> targets) {
		this.cachedAgentTargets = List.copyOf(targets);
		this.cacheExpiresAtNanos = System.nanoTime() + this.cacheTtlNanos;
		log.debug("Updated OpenClaw agent-target cache: size={}, ttlNanos={}",
				targets.size(), this.cacheTtlNanos);
	}

	private static List<String> toAgentTargetIds(OpenClawApi.ListModelResponse response) {
		if (Objects.isNull(response) || Objects.isNull(response.data())) {
			return List.of();
		}
		return response.data().stream().map(OpenClawApi.ModelData::id).toList();
	}
}

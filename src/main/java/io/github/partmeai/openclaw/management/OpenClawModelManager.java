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
 * <p>OpenClaw 智能体目标发现与缓存管理器。</p>
 *
 * <p>通过 {@code GET /v1/models} 查询网关可用的智能体目标，并在本地保存不可变列表快照。
 * 同步查询使用双重检查和互斥锁，保证并发缓存未命中时最多由一个线程刷新；异步查询使用
 * 原子引用保存共享且已缓存的 {@link Mono}，把并发缓存未命中合并为一次上游请求。
 * 刷新失败时返回上一次缓存快照，不向调用方传播发现接口异常。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#model-list-and-agent-routing">Model list and agent routing</a>
 */
@Slf4j
public class OpenClawModelManager {

	/**
	 * <p>默认模型列表缓存有效期，为三十秒。</p>
	 */
	public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

	/**
	 * <p>用于访问模型发现端点的 OpenClaw API 客户端。</p>
	 */
	private final OpenClawApi api;

	/**
	 * <p>缓存有效期的纳秒表示。</p>
	 */
	private final long cacheTtlNanos;

	/**
	 * <p>同步刷新模型列表时使用的互斥监视器。</p>
	 */
	private final Object cacheMonitor = new Object();

	/**
	 * <p>当前正在执行的异步刷新 Publisher。</p>
	 *
	 * <p>非空时，新的异步调用直接加入该 Publisher，从而共享一次上游请求。</p>
	 */
	private final AtomicReference<Mono<List<String>>> refreshInFlight = new AtomicReference<>();

	/**
	 * <p>最近一次成功刷新得到的不可变智能体目标列表。</p>
	 */
	private volatile List<String> cachedAgentTargets = List.of();

	/**
	 * <p>基于 {@link System#nanoTime()} 的缓存过期时刻。</p>
	 */
	private volatile long cacheExpiresAtNanos;

	/**
	 * <p>使用默认缓存有效期创建模型管理器。</p>
	 *
	 * @param api io.github.partmeai.openclaw.api.OpenClawApi 模型发现 API 客户端，不能为 {@code null}
	 */
	public OpenClawModelManager(OpenClawApi api) {
		this(api, DEFAULT_CACHE_TTL);
	}

	/**
	 * <p>使用指定缓存有效期创建模型管理器。</p>
	 *
	 * @param api io.github.partmeai.openclaw.api.OpenClawApi 模型发现 API 客户端，不能为 {@code null}
	 * @param cacheTtl java.time.Duration 缓存有效期，不能为 {@code null} 或负值；零表示缓存立即过期
	 * @throws java.lang.IllegalArgumentException 当任一参数不符合约束时抛出
	 */
	public OpenClawModelManager(OpenClawApi api, Duration cacheTtl) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(cacheTtl, "cacheTtl must not be null");
		Assert.isTrue(!cacheTtl.isNegative(), "cacheTtl must not be negative");
		this.api = api;
		this.cacheTtlNanos = cacheTtl.toNanos();
	}

	/**
	 * <p>同步列出全部可用的智能体目标。</p>
	 *
	 * <p>缓存有效时直接返回快照；缓存过期后在互斥区内再次检查，以避免等待锁期间其他线程
	 * 已完成刷新。调用网关失败时返回上一次缓存列表。</p>
	 *
	 * @return java.util.List&lt;String&gt; 不可变智能体目标标识列表；刷新失败时为上一次缓存快照
	 */
	public List<String> listAgentTargets() {
		List<String> cached = currentCache();
		if (Objects.nonNull(cached)) {
			log.trace("Using cached OpenClaw agent-target list: size={}", cached.size());
			return cached;
		}
		// 锁内二次检查可合并同一过期窗口内的同步刷新请求。
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
	 * <p>异步列出全部可用的智能体目标。</p>
	 *
	 * <p>缓存过期时创建带 {@code cache()} 的刷新 Publisher，并通过 CAS 发布为共享的进行中刷新。
	 * 并发调用要么直接取得该 Publisher，要么在 CAS 竞争失败后取得获胜者发布的 Publisher。
	 * 终止后清除引用，使下一次缓存过期能够创建新刷新。</p>
	 *
	 * @return reactor.core.publisher.Mono&lt;List&lt;String&gt;&gt; 发出不可变智能体目标列表的 Publisher
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
			// 多个订阅者共享上游结果，避免每个订阅者再次请求模型端点。
			.cache();
		if (this.refreshInFlight.compareAndSet(null, refresh)) {
			return refresh;
		}
		return this.refreshInFlight.get();
	}

	/**
	 * <p>查询指定智能体目标的详细信息。</p>
	 *
	 * @param modelId java.lang.String 智能体目标标识
	 * @return java.util.Optional&lt;OpenClawApi.ModelResponse&gt; 查询成功且响应非空时包含模型信息，否则为空
	 */
	public Optional<OpenClawApi.ModelResponse> getAgentTarget(String modelId) {
		try {
			return Optional.ofNullable(this.api.getModel(modelId));
		}
		catch (Exception ex) {
			log.warn("Failed to get agent target '{}': {}", modelId, ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * <p>判断指定智能体目标是否出现在当前可用列表中。</p>
	 *
	 * @param modelId java.lang.String 智能体目标标识
	 * @return boolean 可用列表包含该标识时返回 {@code true}
	 */
	public boolean isAgentTargetAvailable(String modelId) {
		return listAgentTargets().contains(modelId);
	}

	/**
	 * <p>获取 OpenClaw 默认智能体目标标识。</p>
	 *
	 * @return java.lang.String 固定值 {@code openclaw/default}
	 */
	public String getDefaultAgentTarget() {
		return "openclaw/default";
	}

	/**
	 * <p>使当前模型列表缓存立即失效。</p>
	 *
	 * <p>该操作仅重置过期时间，不清空上一次缓存快照；后续刷新失败时仍可回退到该快照。</p>
	 */
	public void invalidateCache() {
		this.cacheExpiresAtNanos = 0;
	}

	/**
	 * <p>读取仍处于有效期内的缓存快照。</p>
	 *
	 * @return java.util.List&lt;String&gt; 有效缓存快照，缓存过期时返回 {@code null}
	 */
	private List<String> currentCache() {
		return System.nanoTime() < this.cacheExpiresAtNanos ? this.cachedAgentTargets : null;
	}

	/**
	 * <p>以不可变快照更新模型列表及其过期时刻。</p>
	 *
	 * @param targets java.util.List&lt;String&gt; 最新智能体目标列表
	 */
	private void updateCache(List<String> targets) {
		this.cachedAgentTargets = List.copyOf(targets);
		this.cacheExpiresAtNanos = System.nanoTime() + this.cacheTtlNanos;
		log.debug("Updated OpenClaw agent-target cache: size={}, ttlNanos={}",
				targets.size(), this.cacheTtlNanos);
	}

	/**
	 * <p>从模型列表响应中提取智能体目标标识。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ListModelResponse 模型列表响应
	 * @return java.util.List&lt;String&gt; 目标标识列表；响应或数据为空时返回空列表
	 */
	private static List<String> toAgentTargetIds(OpenClawApi.ListModelResponse response) {
		if (Objects.isNull(response) || Objects.isNull(response.data())) {
			return List.of();
		}
		return response.data().stream().map(OpenClawApi.ModelData::id).toList();
	}
}

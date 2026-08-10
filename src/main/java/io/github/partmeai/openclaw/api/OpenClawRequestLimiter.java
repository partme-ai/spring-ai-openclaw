package io.github.partmeai.openclaw.api;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * <p>OpenClaw 请求并发门禁。</p>
 *
 * <p>使用原子计数器限制同一 API 客户端实例同时执行的请求数。同步调用在执行请求前占用槽位，
 * 并在 {@code finally} 中释放；响应式调用在订阅时占用槽位，并在完成、失败或取消时通过
 * {@code doFinally} 释放。达到上限时立即以 {@link RejectedExecutionException} 拒绝请求，
 * 不进行排队或等待。</p>
 */
@Slf4j
final class OpenClawRequestLimiter {

	/**
	 * <p>允许同时执行的最大请求数。</p>
	 */
	private final int maxConcurrentRequests;

	/**
	 * <p>当前已占用槽位的请求数。</p>
	 */
	private final AtomicInteger inFlightRequests = new AtomicInteger();

	/**
	 * <p>创建请求并发门禁。</p>
	 *
	 * @param maxConcurrentRequests int 允许同时执行的最大请求数，必须大于零
	 * @throws java.lang.IllegalArgumentException 当最大并发数小于一时抛出
	 */
	OpenClawRequestLimiter(int maxConcurrentRequests) {
		if (maxConcurrentRequests < 1) {
			throw new IllegalArgumentException("maxConcurrentRequests must be greater than zero");
		}
		this.maxConcurrentRequests = maxConcurrentRequests;
		log.debug("Initialized OpenClaw request limiter: maxConcurrentRequests={}", maxConcurrentRequests);
	}

	/**
	 * <p>在并发门禁保护下同步执行请求。</p>
	 *
	 * <p>请求执行期间持续占用一个槽位；无论请求正常返回还是抛出异常，槽位都会被释放。</p>
	 *
	 * @param request java.util.function.Supplier&lt;T&gt; 延迟执行的同步请求
	 * @param <T> 请求结果类型
	 * @return T 请求执行结果
	 * @throws java.util.concurrent.RejectedExecutionException 当当前并发请求数已达到上限时抛出
	 */
	<T> T execute(Supplier<T> request) {
		acquireOrThrow();
		try {
			return request.get();
		}
		finally {
			release();
		}
	}

	/**
	 * <p>为单值响应式请求添加并发门禁。</p>
	 *
	 * <p>槽位在每次订阅时获取，并在流完成、失败或取消时释放；返回 Publisher 本身不会预占槽位。</p>
	 *
	 * @param request reactor.core.publisher.Mono&lt;T&gt; 需要保护的单值请求
	 * @param <T> 响应元素类型
	 * @return reactor.core.publisher.Mono&lt;T&gt; 带并发限制的延迟 Publisher
	 */
	<T> Mono<T> guard(Mono<T> request) {
		// 将槽位获取延迟到订阅阶段，确保每次订阅都独立计数。
		return Mono.defer(() -> {
			if (!tryAcquire()) {
				return Mono.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	/**
	 * <p>为多值响应式请求添加并发门禁。</p>
	 *
	 * <p>槽位覆盖整个流的生命周期，包括正常完成、异常终止和下游取消。</p>
	 *
	 * @param request reactor.core.publisher.Flux&lt;T&gt; 需要保护的多值请求
	 * @param <T> 响应元素类型
	 * @return reactor.core.publisher.Flux&lt;T&gt; 带并发限制的延迟 Publisher
	 */
	<T> Flux<T> guard(Flux<T> request) {
		// doFinally 对所有终止信号均执行一次，适合作为槽位释放点。
		return Flux.defer(() -> {
			if (!tryAcquire()) {
				return Flux.error(rejected());
			}
			return request.doFinally(signal -> release());
		});
	}

	/**
	 * <p>获取当前正在执行或仍处于订阅生命周期内的请求数。</p>
	 *
	 * @return int 当前占用的并发槽位数
	 */
	int getInFlightRequestCount() {
		return this.inFlightRequests.get();
	}

	/**
	 * <p>获取最大并发请求数。</p>
	 *
	 * @return int 最大并发请求数
	 */
	int getMaxConcurrentRequests() {
		return this.maxConcurrentRequests;
	}

	/**
	 * <p>获取一个并发槽位，失败时立即抛出拒绝异常。</p>
	 *
	 * @throws java.util.concurrent.RejectedExecutionException 当没有可用槽位时抛出
	 */
	private void acquireOrThrow() {
		if (!tryAcquire()) {
			throw rejected();
		}
	}

	/**
	 * <p>使用 CAS 尝试获取并发槽位。</p>
	 *
	 * @return boolean 获取成功返回 {@code true}，达到上限返回 {@code false}
	 */
	private boolean tryAcquire() {
		// CAS 失败表示计数已被其他线程修改，重新读取后继续竞争。
		while (true) {
			int current = this.inFlightRequests.get();
			if (current >= this.maxConcurrentRequests) {
				log.debug("Rejecting OpenClaw request: inFlight={}, maxConcurrentRequests={}",
						current, this.maxConcurrentRequests);
				return false;
			}
			if (this.inFlightRequests.compareAndSet(current, current + 1)) {
				if (log.isTraceEnabled()) {
					log.trace("Acquired OpenClaw request slot: inFlight={}/{}",
							current + 1, this.maxConcurrentRequests);
				}
				return true;
			}
		}
	}

	/**
	 * <p>释放一个已获取的并发槽位。</p>
	 */
	private void release() {
		int remaining = this.inFlightRequests.decrementAndGet();
		if (log.isTraceEnabled()) {
			log.trace("Released OpenClaw request slot: inFlight={}/{}",
					remaining, this.maxConcurrentRequests);
		}
	}

	/**
	 * <p>创建包含当前并发上限信息的拒绝异常。</p>
	 *
	 * @return java.util.concurrent.RejectedExecutionException 请求拒绝异常
	 */
	private RejectedExecutionException rejected() {
		return new RejectedExecutionException(
				"OpenClaw concurrent request limit exceeded: " + this.maxConcurrentRequests);
	}
}

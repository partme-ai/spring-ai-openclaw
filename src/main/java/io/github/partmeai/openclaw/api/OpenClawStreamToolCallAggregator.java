package io.github.partmeai.openclaw.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse;
import io.github.partmeai.openclaw.api.OpenClawApi.Message;
import reactor.core.publisher.Flux;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * <p>OpenClaw 流式工具调用片段聚合器。</p>
 *
 * <p>OpenAI 兼容的 SSE 响应可能把同一次工具调用的名称、标识和参数拆分到多个增量块中。
 * 本聚合器仅在检测到工具调用后建立窗口，并在带有结束原因的块到达时关闭窗口；普通文本块
 * 保持逐块输出。窗口内按 choice、message、tool call 及 function 层级合并，文本字段按到达
 * 顺序拼接，其他元数据优先采用较新的非空值。</p>
 */
final class OpenClawStreamToolCallAggregator {

	/**
	 * <p>聚合流中的工具调用增量块。</p>
	 *
	 * <p>使用 {@link Flux#defer(java.util.function.Supplier)} 为每次订阅创建独立状态，避免不同
	 * 订阅之间共享“正在处理工具调用”的标记。</p>
	 *
	 * @param chunks reactor.core.publisher.Flux&lt;ChatResponse&gt; 原始 SSE 响应块
	 * @return reactor.core.publisher.Flux&lt;ChatResponse&gt; 工具调用已按窗口合并的响应流
	 */
	Flux<ChatResponse> aggregate(Flux<ChatResponse> chunks) {
		return Flux.defer(() -> {
			// 该状态只属于当前订阅，用于决定 windowUntil 的边界。
			AtomicBoolean insideToolCall = new AtomicBoolean();
			return chunks
				.doOnNext(chunk -> {
					if (OpenClawApiHelper.isStreamingToolCall(chunk)) {
						insideToolCall.set(true);
					}
				})
				.windowUntil(chunk -> {
					if (insideToolCall.get() && OpenClawApiHelper.isStreamingDone(chunk)) {
						insideToolCall.set(false);
						return true;
					}
					return !insideToolCall.get();
				})
				.concatMap(window -> window.reduce(this::merge));
		});
	}

	/**
	 * <p>合并两个连续的聊天响应块。</p>
	 *
	 * @param previous io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 已累计的响应块
	 * @param current io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 当前响应块
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 合并后的响应
	 */
	ChatResponse merge(ChatResponse previous, ChatResponse current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		ChatResponse.Choice choice = merge(firstChoice(previous), firstChoice(current));
		return new ChatResponse(prefer(current.id(), previous.id()), prefer(current.object(), previous.object()),
				prefer(current.created(), previous.created()), prefer(current.model(), previous.model()),
				Objects.isNull(choice) ? List.of() : List.of(choice),
				prefer(current.usage(), previous.usage()));
	}

	/**
	 * <p>合并两个首选项增量。</p>
	 *
	 * @param previous io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Choice 已累计的选项
	 * @param current io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Choice 当前选项
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Choice 合并后的选项
	 */
	private ChatResponse.Choice merge(ChatResponse.Choice previous, ChatResponse.Choice current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new ChatResponse.Choice(prefer(current.index(), previous.index()),
				merge(previous.message(), current.message()), merge(previous.delta(), current.delta()),
				prefer(current.finishReason(), previous.finishReason()));
	}

	/**
	 * <p>合并两个消息增量。</p>
	 *
	 * @param previous io.github.partmeai.openclaw.api.OpenClawApi.Message 已累计的消息
	 * @param current io.github.partmeai.openclaw.api.OpenClawApi.Message 当前消息
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message 合并后的消息
	 */
	private Message merge(Message previous, Message current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new Message(prefer(current.role(), previous.role()),
				append(previous.content(), current.content()),
				mergeToolCalls(previous.toolCalls(), current.toolCalls()),
				prefer(current.toolCallId(), previous.toolCallId()),
				prefer(current.name(), previous.name()));
	}

	/**
	 * <p>按工具调用索引合并增量列表。</p>
	 *
	 * <p>显式 {@code index} 优先；缺少索引时尝试按调用 ID 匹配，否则追加到列表末尾。</p>
	 *
	 * @param previous java.util.List&lt;Message.ToolCall&gt; 已累计的工具调用
	 * @param current java.util.List&lt;Message.ToolCall&gt; 当前工具调用片段
	 * @return java.util.List&lt;Message.ToolCall&gt; 已合并且不包含空占位的工具调用列表
	 */
	private List<Message.ToolCall> mergeToolCalls(List<Message.ToolCall> previous,
			List<Message.ToolCall> current) {
		if (CollectionUtils.isEmpty(previous)) {
			return current;
		}
		if (CollectionUtils.isEmpty(current)) {
			return previous;
		}

		List<Message.ToolCall> merged = new ArrayList<>(previous);
		for (Message.ToolCall fragment : current) {
			int index = resolveIndex(fragment, merged);
			while (merged.size() <= index) {
				merged.add(null);
			}
			merged.set(index, merge(merged.get(index), fragment));
		}
		return merged.stream().filter(Objects::nonNull).toList();
	}

	/**
	 * <p>确定工具调用片段在累计列表中的位置。</p>
	 *
	 * @param fragment io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCall 当前片段
	 * @param previous java.util.List&lt;Message.ToolCall&gt; 已累计的工具调用
	 * @return int 片段应合并到的非负索引
	 */
	private int resolveIndex(Message.ToolCall fragment, List<Message.ToolCall> previous) {
		if (Objects.nonNull(fragment.index())) {
			return fragment.index();
		}
		if (StringUtils.hasText(fragment.id())) {
			for (int i = 0; i < previous.size(); i++) {
				Message.ToolCall candidate = previous.get(i);
				if (Objects.nonNull(candidate) && fragment.id().equals(candidate.id())) {
					return i;
				}
			}
			return previous.size();
		}
		return Math.max(previous.size() - 1, 0);
	}

	/**
	 * <p>合并两个工具调用片段。</p>
	 *
	 * @param previous io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCall 已累计的片段
	 * @param current io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCall 当前片段
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCall 合并后的工具调用
	 */
	private Message.ToolCall merge(Message.ToolCall previous, Message.ToolCall current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		return new Message.ToolCall(prefer(current.index(), previous.index()),
				preferText(current.id(), previous.id()), prefer(current.type(), previous.type()),
				merge(previous.function(), current.function()));
	}

	/**
	 * <p>合并工具函数名称和参数片段。</p>
	 *
	 * @param previous io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCallFunction 已累计函数
	 * @param current io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCallFunction 当前函数片段
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.Message.ToolCallFunction 合并后的函数信息
	 */
	private Message.ToolCallFunction merge(Message.ToolCallFunction previous,
			Message.ToolCallFunction current) {
		if (Objects.isNull(previous)) {
			return current;
		}
		if (Objects.isNull(current)) {
			return previous;
		}
		return new Message.ToolCallFunction(preferText(current.name(), previous.name()),
				append(previous.arguments(), current.arguments()));
	}

	/**
	 * <p>取得响应中的第一个选项。</p>
	 *
	 * @param response io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse 响应块
	 * @return io.github.partmeai.openclaw.api.OpenClawApi.ChatResponse.Choice 第一个选项，不存在时返回 {@code null}
	 */
	private ChatResponse.Choice firstChoice(ChatResponse response) {
		return CollectionUtils.isEmpty(response.choices()) ? null : response.choices().get(0);
	}

	/**
	 * <p>按到达顺序拼接可空文本片段。</p>
	 *
	 * @param previous java.lang.String 已累计文本
	 * @param current java.lang.String 当前文本片段
	 * @return java.lang.String 非空的拼接结果
	 */
	private String append(String previous, String current) {
		return (Objects.isNull(previous) ? "" : previous) + (Objects.isNull(current) ? "" : current);
	}

	/**
	 * <p>优先选择包含实际文本的值。</p>
	 *
	 * @param preferred java.lang.String 首选文本
	 * @param fallback java.lang.String 备用文本
	 * @return java.lang.String 首选文本有内容时返回首选值，否则返回备用值
	 */
	private String preferText(String preferred, String fallback) {
		return StringUtils.hasText(preferred) ? preferred : fallback;
	}

	/**
	 * <p>优先选择非空值。</p>
	 *
	 * @param preferred T 首选值
	 * @param fallback T 备用值
	 * @param <T> 值类型
	 * @return T 首选值非空时返回首选值，否则返回备用值
	 */
	private <T> T prefer(T preferred, T fallback) {
		return Objects.nonNull(preferred) ? preferred : fallback;
	}
}

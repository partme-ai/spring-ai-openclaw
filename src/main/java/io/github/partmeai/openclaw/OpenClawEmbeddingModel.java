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

package io.github.partmeai.openclaw;

import java.util.List;
import java.util.Map;

import io.github.partmeai.openclaw.api.OpenClawApi;
import io.github.partmeai.openclaw.api.OpenClawChatOptions;
import io.github.partmeai.openclaw.api.OpenClawModel;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.util.Assert;

/**
 * <p>面向 OpenClaw Gateway 的 Spring AI {@link EmbeddingModel} 实现。</p>
 *
 * <p>通过 {@code POST /v1/embeddings} 创建嵌入向量，并把协议中的浮点列表和 token usage
 * 映射为 Spring AI 对象。模型字段使用 OpenClaw 智能体目标；调用方可通过
 * {@link OpenClawChatOptions#setXOpenclawModel(String)} 请求头选项覆盖后端嵌入模型。</p>
 *
 * @author <a href="https://github.com/loong10k">Loong Wan</a>
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
@Slf4j
public class OpenClawEmbeddingModel implements EmbeddingModel {

	/** <p>执行嵌入请求的 OpenClaw API 客户端。</p> */
	private final OpenClawApi api;

	/** <p>与运行时选项合并的默认选项。</p> */
	private final OpenClawChatOptions defaultOptions;

	/**
	 * <p>创建 OpenClaw 嵌入模型。</p>
	 * @param api io.github.partmeai.openclaw.api.OpenClawApi API 客户端
	 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
	 * @throws java.lang.IllegalArgumentException 当任一参数为 {@code null} 时抛出
	 */
	public OpenClawEmbeddingModel(OpenClawApi api, OpenClawChatOptions defaultOptions) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		this.api = api;
		this.defaultOptions = defaultOptions;
	}

	/**
	 * <p>创建嵌入模型构建器。</p>
	 * @return io.github.partmeai.openclaw.OpenClawEmbeddingModel.Builder 新构建器
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * <p>批量创建嵌入向量。</p>
	 *
	 * <p>将协议响应中的每个 {@link Float} 列表复制为 {@code float[]}，并保留响应索引；
	 * 响应未包含 data 时返回空结果列表。</p>
	 * @param request org.springframework.ai.embedding.EmbeddingRequest 嵌入请求
	 * @return org.springframework.ai.embedding.EmbeddingResponse 嵌入结果与 usage 元数据
	 */
	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		Assert.notNull(request, "request must not be null");

		OpenClawChatOptions requestOptions = mergeOptions(request.getOptions());
		Map<String, String> headers = requestOptions.toHttpHeaders();

		OpenClawApi.EmbeddingsRequest apiRequest = new OpenClawApi.EmbeddingsRequest(
				requestOptions.getModel(),
				request.getInstructions(),
				null,
				null);

		OpenClawApi.EmbeddingsResponse apiResponse = this.api.embed(apiRequest, headers);

		// 协议使用装箱 Float 列表，Spring AI 使用原始 float 数组，需要逐元素复制。
		List<Embedding> embeddings = List.of();
		if (apiResponse.data() != null) {
			embeddings = apiResponse.data().stream()
				.map(data -> {
					float[] vector = new float[data.embedding().size()];
					for (int i = 0; i < data.embedding().size(); i++) {
						vector[i] = data.embedding().get(i);
					}
					return new Embedding(vector, data.index());
				})
				.toList();
		}

		EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(
				apiResponse.model(),
				new org.springframework.ai.chat.metadata.DefaultUsage(
					apiResponse.usage() != null ? apiResponse.usage().promptTokens() : 0,
					apiResponse.usage() != null ? apiResponse.usage().completionTokens() : 0));

		return new EmbeddingResponse(embeddings, metadata);
	}

	/**
	 * <p>为单个文本创建嵌入向量。</p>
	 * @param text java.lang.String 输入文本
	 * @return float[] 第一个嵌入向量；响应无结果时返回空数组
	 */
	@Override
	public float[] embed(String text) {
		Assert.notNull(text, "text must not be null");
		var response = call(new EmbeddingRequest(List.of(text), null));
		if (response.getResults().isEmpty()) {
			return new float[0];
		}
		return response.getResults().get(0).getOutput();
	}

	/**
	 * <p>为文档文本创建嵌入向量。</p>
	 * @param document org.springframework.ai.document.Document 输入文档
	 * @return float[] 文档文本的嵌入向量
	 */
	@Override
	public float[] embed(Document document) {
		Assert.notNull(document, "document must not be null");
		return embed(document.getText());
	}

	/**
	 * <p>为文本列表批量创建嵌入向量。</p>
	 * @param texts java.util.List&lt;String&gt; 输入文本列表
	 * @return java.util.List&lt;float[]&gt; 与响应结果顺序一致的向量列表
	 */
	@Override
	public List<float[]> embed(List<String> texts) {
		Assert.notNull(texts, "texts must not be null");
		return call(new EmbeddingRequest(texts, null)).getResults().stream()
			.map(Embedding::getOutput)
			.toList();
	}

	/**
	 * <p>合并运行时选项与默认选项。</p>
	 * @param runtimeOptions org.springframework.ai.embedding.EmbeddingOptions 运行时选项
	 * @return io.github.partmeai.openclaw.api.OpenClawChatOptions 实际请求选项
	 */
	private OpenClawChatOptions mergeOptions(EmbeddingOptions runtimeOptions) {
		OpenClawChatOptions merged;
		if (runtimeOptions instanceof OpenClawChatOptions ocOpts) {
			merged = OpenClawChatOptions.fromOptions(ocOpts);
		}
		else {
			merged = OpenClawChatOptions.builder().build();
		}
		merged = OpenClawChatOptions.merge(merged, this.defaultOptions);
		if (merged.getModel() == null || merged.getModel().isEmpty()) {
			merged.setModel(OpenClawModel.DEFAULT.id());
		}
		return merged;
	}

	/**
	 * <p>返回模型声明的固定嵌入维度。</p>
	 *
	 * <p>当前客户端不预先声明后端模型维度，因此返回 {@code 0}。</p>
	 * @return int 固定返回 {@code 0}
	 */
	@Override
	public int dimensions() {
		return 0;
	}

	/** <p>{@link OpenClawEmbeddingModel} 构建器。</p> */
	public static final class Builder {

		/** <p>OpenClaw API 客户端。</p> */
		private OpenClawApi api;

		/** <p>默认嵌入选项。</p> */
		private OpenClawChatOptions defaultOptions = OpenClawChatOptions.builder()
				.model(OpenClawModel.DEFAULT.id()).build();

		private Builder() {
		}

		/**
		 * <p>设置 API 客户端。</p>
		 * @param api io.github.partmeai.openclaw.api.OpenClawApi API 客户端
		 * @return io.github.partmeai.openclaw.OpenClawEmbeddingModel.Builder 当前构建器
		 */
		public Builder api(OpenClawApi api) {
			this.api = api;
			return this;
		}

		/**
		 * <p>设置默认嵌入选项。</p>
		 * @param defaultOptions io.github.partmeai.openclaw.api.OpenClawChatOptions 默认选项
		 * @return io.github.partmeai.openclaw.OpenClawEmbeddingModel.Builder 当前构建器
		 */
		public Builder defaultOptions(OpenClawChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * <p>构建 OpenClaw 嵌入模型。</p>
		 * @return io.github.partmeai.openclaw.OpenClawEmbeddingModel 新模型实例
		 */
		public OpenClawEmbeddingModel build() {
			return new OpenClawEmbeddingModel(this.api, this.defaultOptions);
		}
	}
}

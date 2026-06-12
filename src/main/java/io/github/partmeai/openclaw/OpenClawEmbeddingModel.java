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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@link EmbeddingModel} implementation for OpenClaw Gateway.
 * <p>
 * Uses the OpenClaw Gateway's {@code POST /v1/embeddings} endpoint with
 * agent-target model routing. The {@code model} field uses OpenClaw agent-target
 * ids ({@code openclaw/default}, {@code openclaw/<agentId>}).
 * Use {@code x-openclaw-model} via {@link OpenClawChatOptions#setXOpenclawModel(String)}
 * to override the backend embedding model.
 *
 * @author Loong Wan
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api">OpenClaw OpenAI HTTP API</a>
 */
public class OpenClawEmbeddingModel implements EmbeddingModel {

	private static final Logger logger = LoggerFactory.getLogger(OpenClawEmbeddingModel.class);

	private final OpenClawApi api;

	private final OpenClawChatOptions defaultOptions;

	public OpenClawEmbeddingModel(OpenClawApi api, OpenClawChatOptions defaultOptions) {
		Assert.notNull(api, "api must not be null");
		Assert.notNull(defaultOptions, "defaultOptions must not be null");
		this.api = api;
		this.defaultOptions = defaultOptions;
	}

	public static Builder builder() {
		return new Builder();
	}

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

	@Override
	public float[] embed(String text) {
		Assert.notNull(text, "text must not be null");
		var response = call(new EmbeddingRequest(List.of(text), null));
		if (response.getResults().isEmpty()) {
			return new float[0];
		}
		return response.getResults().get(0).getOutput();
	}

	@Override
	public float[] embed(Document document) {
		Assert.notNull(document, "document must not be null");
		return embed(document.getText());
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		Assert.notNull(texts, "texts must not be null");
		return call(new EmbeddingRequest(texts, null)).getResults().stream()
			.map(Embedding::getOutput)
			.toList();
	}

	private OpenClawChatOptions mergeOptions(EmbeddingOptions runtimeOptions) {
		OpenClawChatOptions merged;
		if (runtimeOptions instanceof OpenClawChatOptions ocOpts) {
			merged = OpenClawChatOptions.fromOptions(ocOpts);
		}
		else {
			merged = OpenClawChatOptions.builder().build();
		}
		merged = ModelOptionsUtils.merge(merged, this.defaultOptions, OpenClawChatOptions.class);
		if (merged.getModel() == null || merged.getModel().isEmpty()) {
			merged.setModel(OpenClawModel.DEFAULT.id());
		}
		return merged;
	}

	@Override
	public int dimensions() {
		return 0;
	}

	public static final class Builder {

		private OpenClawApi api;

		private OpenClawChatOptions defaultOptions = OpenClawChatOptions.builder()
				.model(OpenClawModel.DEFAULT.id()).build();

		private Builder() {
		}

		public Builder api(OpenClawApi api) {
			this.api = api;
			return this;
		}

		public Builder defaultOptions(OpenClawChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		public OpenClawEmbeddingModel build() {
			return new OpenClawEmbeddingModel(this.api, this.defaultOptions);
		}
	}
}

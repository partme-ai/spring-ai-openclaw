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

package io.github.partmeai.openclaw.api;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * <p>聊天模型的思考选项。</p>
 *
 * <p>布尔形式用于只支持启用/禁用的模型；字符串形式用于要求 {@code low}、{@code medium}
 * 或 {@code high} 等级的模型。自定义 Jackson 处理器把实现直接映射为 JSON 布尔值或字符串，
 * 不增加包装对象。</p>
 *
 * @author Mark Pollack
 * @since 1.1.0
 * @see ThinkBoolean
 * @see ThinkLevel
 */
@JsonSerialize(using = ThinkOption.ThinkOptionSerializer.class)
@JsonDeserialize(using = ThinkOption.ThinkOptionDeserializer.class)
public sealed interface ThinkOption {

	/**
	 * <p>转换为直接写入 JSON 的值。</p>
	 * @return java.lang.Object 布尔值或字符串
	 */
	Object toJsonValue();

	/** <p>把思考选项写成原始布尔值或字符串的序列化器。</p> */
	class ThinkOptionSerializer extends JsonSerializer<ThinkOption> {

		/**
		 * <p>序列化思考选项。</p>
		 * @param value io.github.partmeai.openclaw.api.ThinkOption 待序列化选项
		 * @param gen com.fasterxml.jackson.core.JsonGenerator JSON 生成器
		 * @param serializers com.fasterxml.jackson.databind.SerializerProvider 序列化上下文
		 * @throws java.io.IOException 当 JSON 写入失败时抛出
		 */
		@Override
		public void serialize(ThinkOption value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
			if (value == null) {
				gen.writeNull();
			}
			else {
				gen.writeObject(value.toJsonValue());
			}
		}

	}

	/** <p>把 JSON 布尔值或字符串读取为思考选项的反序列化器。</p> */
	class ThinkOptionDeserializer extends JsonDeserializer<ThinkOption> {

		/**
		 * <p>根据当前 JSON token 反序列化思考选项。</p>
		 * @param p com.fasterxml.jackson.core.JsonParser JSON 解析器
		 * @param ctxt com.fasterxml.jackson.databind.DeserializationContext 反序列化上下文
		 * @return io.github.partmeai.openclaw.api.ThinkOption 对应选项；JSON null 返回 {@code null}
		 * @throws java.io.IOException 当 token 不是布尔值、字符串或 null 时抛出
		 */
		@Override
		public ThinkOption deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			JsonToken token = p.currentToken();
			if (token == JsonToken.VALUE_TRUE) {
				return ThinkBoolean.ENABLED;
			}
			else if (token == JsonToken.VALUE_FALSE) {
				return ThinkBoolean.DISABLED;
			}
			else if (token == JsonToken.VALUE_STRING) {
				return new ThinkLevel(p.getValueAsString());
			}
			else if (token == JsonToken.VALUE_NULL) {
				return null;
			}
			throw new IOException("Cannot deserialize ThinkOption from token: " + token);
		}

	}

	/**
	 * <p>布尔型思考选项。</p>
	 * @param enabled boolean 是否启用思考
	 */
	record ThinkBoolean(boolean enabled) implements ThinkOption {

		/** <p>启用思考的预定义值。</p> */
		public static final ThinkBoolean ENABLED = new ThinkBoolean(true);

		/** <p>禁用思考的预定义值。</p> */
		public static final ThinkBoolean DISABLED = new ThinkBoolean(false);

		/**
		 * <p>转换为 JSON 布尔值。</p>
		 * @return java.lang.Object 当前 {@code enabled} 布尔值
		 */
		@Override
		public Object toJsonValue() {
			return this.enabled;
		}

	}

	/**
	 * <p>字符串等级思考选项。</p>
	 * @param level java.lang.String {@code low}、{@code medium} 或 {@code high}；也可为 {@code null}
	 */
	record ThinkLevel(String level) implements ThinkOption {

		/** <p>合法思考等级。</p> */
		private static final List<String> VALID_LEVELS = List.of("low", "medium", "high");

		/** <p>低思考等级预定义值。</p> */
		public static final ThinkLevel LOW = new ThinkLevel("low");

		/** <p>中思考等级预定义值。</p> */
		public static final ThinkLevel MEDIUM = new ThinkLevel("medium");

		/** <p>高思考等级预定义值。</p> */
		public static final ThinkLevel HIGH = new ThinkLevel("high");

		/**
		 * <p>校验思考等级。</p>
		 * @param level java.lang.String 思考等级，可为 {@code null}
		 * @throws java.lang.IllegalArgumentException 当非空等级不在合法列表中时抛出
		 */
		public ThinkLevel {
			if (level != null && !VALID_LEVELS.contains(level)) {
				throw new IllegalArgumentException("think level must be one of " + VALID_LEVELS + ", got: " + level);
			}
		}

		/**
		 * <p>转换为 JSON 字符串值。</p>
		 * @return java.lang.Object 当前等级字符串
		 */
		@Override
		public Object toJsonValue() {
			return this.level;
		}

	}

}

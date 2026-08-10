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

package io.github.partmeai.openclaw.aot;

import io.github.partmeai.openclaw.api.ThinkOption;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import static org.springframework.ai.aot.AiRuntimeHints.findJsonAnnotatedClassesInPackage;

/**
 * <p>OpenClaw 原生镜像运行时提示注册器。</p>
 *
 * <p>为 OpenClaw 包内带 JSON 注解的类型及自定义思考选项序列化器注册全部成员反射访问，
 * 使 AOT/原生镜像运行时能够完成 Jackson 序列化与反序列化。</p>
 */
public class OpenClawRuntimeHints implements RuntimeHintsRegistrar {

	/**
	 * <p>注册 OpenClaw JSON 类型的反射提示。</p>
	 * @param hints org.springframework.aot.hint.RuntimeHints 运行时提示注册表
	 * @param classLoader java.lang.ClassLoader AOT 分析使用的类加载器
	 */
	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		// JSON 模型统一注册全部成员类别，另显式覆盖注解引用的自定义处理器。
		var mcs = MemberCategory.values();
		for (var tr : findJsonAnnotatedClassesInPackage("io.github.partmeai.openclaw")) {
			hints.reflection().registerType(tr, mcs);
		}
		hints.reflection().registerType(ThinkOption.ThinkOptionDeserializer.class, mcs);
		hints.reflection().registerType(ThinkOption.ThinkOptionSerializer.class, mcs);
	}
}

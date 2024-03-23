/*
 * Copyright 2002-2024 the original author or authors.
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

package org.springframework.core.metrics;

import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.function.Supplier;

/**
 * 默认“no op”{@code ApplicationStartup} 实现。
 *
 * <p>此变体旨在最小化开销，并且不记录事件。
 *
 * @author Brian Clozel
 */
class DefaultApplicationStartup implements ApplicationStartup {

	/**
	 * 默认的启动步骤
	 */
	private static final DefaultStartupStep DEFAULT_STARTUP_STEP = new DefaultStartupStep();

	/**
	 * 开始一个新的启动步骤。
	 *
	 * @param name 启动步骤的名称
	 * @return 默认的启动步骤实例
	 */
	@Override
	public DefaultStartupStep start(String name) {
		return DEFAULT_STARTUP_STEP;
	}


	/**
	 * 默认的启动步骤实现类。
	 */
	static class DefaultStartupStep implements StartupStep {

		// 默认的标签
		private final DefaultTags TAGS = new DefaultTags();

		/**
		 * 获取启动步骤的名称。
		 *
		 * @return 启动步骤的名称，此处为 "default"
		 */
		@Override
		public String getName() {
			return "default";
		}

		/**
		 * 获取启动步骤的唯一标识符。
		 *
		 * @return 启动步骤的唯一标识符，此处为固定值 0
		 */
		@Override
		public long getId() {
			return 0L;
		}

		/**
		 * 获取父启动步骤的唯一标识符。
		 *
		 * @return 父启动步骤的唯一标识符，此处为 null
		 */
		@Override
		@Nullable
		public Long getParentId() {
			return null;
		}

		/**
		 * 获取启动步骤的标签。
		 *
		 * @return 默认的标签实例
		 */
		@Override
		public Tags getTags() {
			return this.TAGS;
		}


		/**
		 * 向启动步骤添加标签。
		 *
		 * @param key   标签的键
		 * @param value 标签的值
		 * @return 该启动步骤实例
		 */
		@Override
		public StartupStep tag(String key, String value) {
			return this;
		}


		/**
		 * 向启动步骤添加标签。
		 *
		 * @param key   标签的键
		 * @param value 标签的值
		 * @return 该启动步骤实例
		 */
		@Override
		public StartupStep tag(String key, Supplier<String> value) {
			return this;
		}

		/**
		 * 结束当前启动步骤。
		 */
		@Override
		public void end() {
			// 此处不执行任何操作，因为这是一个默认的启动步骤，无需结束。
		}


		/**
		 * 默认的标签实现类，用于表示启动步骤的标签。
		 */
		static class DefaultTags implements StartupStep.Tags {

			/**
			 * 获取标签的迭代器。
			 *
			 * @return 标签的迭代器，此处返回空迭代器
			 */
			@Override
			public Iterator<StartupStep.Tag> iterator() {
				return Collections.emptyIterator();
			}
		}
	}

}

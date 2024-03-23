/*
 * Copyright 2002-2020 the original author or authors.
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

/**
 * 使用{@link StartupStep 步骤}检测应用程序启动阶段。
 * <p>核心容器及其基础设施组件可以使用{@code ApplicationStartup}
 * 标记应用程序启动期间的步骤并收集有关执行上下文的数据
 * 或他们的处理时间。
 *
 * @author Brian Clozel
 * @since 5.3
 */
public interface ApplicationStartup {

	/**
	 * 默认“无操作”{@code ApplicationStartup} 实现。
	 * <p>此变体旨在最小化开销，并且不记录数据。
	 */
	ApplicationStartup DEFAULT = new DefaultApplicationStartup();

	/**
	 * 创建一个新步骤并标记其开始。
	 * <p>步骤名称描述当前操作或阶段。 这项技术
	 * 名称应为“.” 命名空间，可以重用来描述其他实例
	 * 应用程序启动期间的相同步骤。
	 *
	 * @param name 步骤名称
	 */
	StartupStep start(String name);

}

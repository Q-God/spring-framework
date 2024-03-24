/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.context;

import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.core.env.EnvironmentCapable;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.lang.Nullable;

/**
 * Central interface to provide configuration for an application.
 * This is read-only while the application is running, but may be
 * reloaded if the implementation supports this.
 *
 * <p>An ApplicationContext provides:
 * <ul>
 * <li>Bean factory methods for accessing application components.
 * Inherited from {@link org.springframework.beans.factory.ListableBeanFactory}.
 * <li>The ability to load file resources in a generic fashion.
 * Inherited from the {@link org.springframework.core.io.ResourceLoader} interface.
 * <li>The ability to publish events to registered listeners.
 * Inherited from the {@link ApplicationEventPublisher} interface.
 * <li>The ability to resolve messages, supporting internationalization.
 * Inherited from the {@link MessageSource} interface.
 * <li>Inheritance from a parent context. Definitions in a descendant context
 * will always take priority. This means, for example, that a single parent
 * context can be used by an entire web application, while each servlet has
 * its own child context that is independent of that of any other servlet.
 * </ul>
 *
 * <p>In addition to standard {@link org.springframework.beans.factory.BeanFactory}
 * lifecycle capabilities, ApplicationContext implementations detect and invoke
 * {@link ApplicationContextAware} beans as well as {@link ResourceLoaderAware},
 * {@link ApplicationEventPublisherAware} and {@link MessageSourceAware} beans.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see ConfigurableApplicationContext
 * @see org.springframework.beans.factory.BeanFactory
 * @see org.springframework.core.io.ResourceLoader
 */
public interface ApplicationContext extends EnvironmentCapable, ListableBeanFactory, HierarchicalBeanFactory,
		MessageSource, ApplicationEventPublisher, ResourcePatternResolver {

	/**
	 * 返回此应用程序上下文的唯一 id。
	 *
	 * @return 上下文的唯一 id，如果没有则返回 {@code null}
	 */
	@Nullable
	String getId();

	/**
	 * 返回此上下文所属的部署应用程序的名称。
	 *
	 * @return 部署应用程序的名称，默认情况下返回空字符串
	 */
	String getApplicationName();

	/**
	 * 返回此上下文的友好名称。
	 *
	 * @return 此上下文的显示名称（从不为 {@code null}）
	 */
	String getDisplayName();

	/**
	 * 返回此上下文首次加载时的时间戳。
	 *
	 * @return 上下文首次加载时的时间戳（毫秒）
	 */
	long getStartupDate();


	/**
	 * 返回父上下文，如果没有父上下文且这是上下文层次结构的根，则返回 {@code null}。
	 *
	 * @return 父上下文，如果没有父上下文且这是根，则返回 {@code null}
	 */
	@Nullable
	ApplicationContext getParent();

	/**
	 * 暴露此上下文的 AutowireCapableBeanFactory 功能。
	 * <p>通常不会由应用程序代码使用，除非为初始化位于应用程序上下文之外的 bean 实例，
	 * 对它们应用 Spring bean 生命周期（完全或部分）。
	 * <p>另外，由 {@link ConfigurableApplicationContext} 接口暴露的内部 BeanFactory
	 * 也提供对 {@link AutowireCapableBeanFactory} 接口的访问。此方法主要作为 ApplicationContext
	 * 接口的便捷、特定的功能。
	 * <p><b>注意：从 4.2 开始，一旦应用程序上下文已关闭，此方法将始终抛出 IllegalStateException。</b>
	 * 在当前 Spring Framework 版本中，只有可刷新的应用程序上下文才会表现出这种行为；从 4.2 开始，
	 * 所有应用程序上下文实现都将被要求遵守。
	 *
	 * @return 此上下文的 AutowireCapableBeanFactory
	 * @throws IllegalStateException 如果上下文不支持 AutowireCapableBeanFactory 接口，
	 *                               或者尚未持有可自动装配的 bean 工厂（例如，如果从未调用 refresh()），
	 *                               或者上下文已经关闭
	 * @see ConfigurableApplicationContext#refresh()
	 * @see ConfigurableApplicationContext#getBeanFactory()
	 */
	AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException;

}

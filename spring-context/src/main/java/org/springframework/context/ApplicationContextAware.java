/*
 * Copyright 2002-2012 the original author or authors.
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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.Aware;

/**
 * 希望被通知所在的 {@link ApplicationContext} 的任何对象实现的接口。
 *
 * <p>实现此接口在某些情况下是有意义的，例如当对象需要访问一组协作的 bean 时。
 * 注意，通过 bean 引用进行配置比仅仅为了进行 bean 查找而实现此接口更为合适。
 *
 * <p>如果对象需要访问文件资源，即想要调用 {@code getResource}，想要发布应用程序事件，
 * 或者需要访问 MessageSource，则也可以实现此接口。然而，在这种具体情况下，
 * <p>
 * 最好实现更具体的 {@link ResourceLoaderAware}、{@link ApplicationEventPublisherAware}
 * <p>
 * 或 {@link MessageSourceAware} 接口。
 *
 * <p>注意，文件资源依赖项也可以公开为类型为 {@link org.springframework.core.io.Resource} 的 bean 属性，
 * 通过字符串进行自动类型转换由 bean 工厂进行填充。这样就不需要为了访问特定文件资源而实现任何回调接口了。
 *
 * <p>{@link org.springframework.context.support.ApplicationObjectSupport} 是应用程序对象的方便基类，
 * 它实现了此接口。
 *
 * <p>有关所有 bean 生命周期方法的列表，请参阅
 * {@link org.springframework.beans.factory.BeanFactory BeanFactory javadocs}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Chris Beams
 * @see ResourceLoaderAware
 * @see ApplicationEventPublisherAware
 * @see MessageSourceAware
 * @see org.springframework.context.support.ApplicationObjectSupport
 * @see org.springframework.beans.factory.BeanFactoryAware
 */
public interface ApplicationContextAware extends Aware {

	/**
	 * 设置此对象所在的 ApplicationContext。
	 * 通常，此调用将用于初始化对象。
	 * <p>在普通 bean 属性填充之后但在初始化回调之前调用，例如
	 * {@link org.springframework.beans.factory.InitializingBean#afterPropertiesSet()} 或自定义的 init 方法之前调用。
	 * 如果适用，会在 {@link ResourceLoaderAware#setResourceLoader}、{@link ApplicationEventPublisherAware#setApplicationEventPublisher}
	 * 和 {@link MessageSourceAware} 之后调用。
	 *
	 * @param applicationContext 此对象要使用的 ApplicationContext 对象
	 * @throws ApplicationContextException 如果出现上下文初始化错误
	 * @throws BeansException              如果由应用程序上下文方法抛出
	 * @see org.springframework.beans.factory.BeanInitializationException
	 */
	void setApplicationContext(ApplicationContext applicationContext) throws BeansException;

}

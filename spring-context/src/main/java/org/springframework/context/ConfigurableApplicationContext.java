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

package org.springframework.context;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ProtocolResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.lang.Nullable;

import java.io.Closeable;
import java.util.concurrent.Executor;

/**
 * SPI 接口，大多数（如果不是全部）应用程序上下文都应该实现。
 * 提供了配置应用程序上下文的功能，除了 {@link org.springframework.context.ApplicationContext} 接口中的应用程序上下文客户端方法之外。
 *
 * <p>配置和生命周期方法被封装在这里，以避免对 ApplicationContext 客户端代码显式暴露。
 * 这些方法只应该由启动和关闭代码使用。
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Sam Brannen
 * @since 03.11.2003
 */
public interface ConfigurableApplicationContext extends ApplicationContext, Lifecycle, Closeable {

	/**
	 * 任意数量的这些字符被认为是单个字符串值中多个上下文配置路径之间的分隔符。
	 *
	 * @see org.springframework.context.support.AbstractXmlApplicationContext#setConfigLocation
	 * @see org.springframework.web.context.ContextLoader#CONFIG_LOCATION_PARAM
	 * @see org.springframework.web.servlet.FrameworkServlet#setContextConfigLocation
	 */
	String CONFIG_LOCATION_DELIMITERS = ",; \t\n";

	/**
	 * 上下文中{@link Executor 引导执行器} bean 的名称。
	 * 如果没有提供，则不会启用后台引导。
	 *
	 * @see java.util.concurrent.Executor
	 * @see org.springframework.core.task.TaskExecutor
	 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory#setBootstrapExecutor
	 * @since 6.2
	 */
	String BOOTSTRAP_EXECUTOR_BEAN_NAME = "bootstrapExecutor";

	/**
	 * 工厂中 ConversionService bean 的名称。
	 * 如果没有提供，则使用默认转换规则。
	 *
	 * @see org.springframework.core.convert.ConversionService
	 * @since 3.0
	 */
	String CONVERSION_SERVICE_BEAN_NAME = "conversionService";

	/**
	 * 工厂中 LoadTimeWeaver bean 的名称。
	 * 如果提供了这样一个 bean，上下文将使用一个临时的类加载器进行类型匹配，
	 * 以便允许 LoadTimeWeaver 处理所有实际的 bean 类。
	 *
	 * @see org.springframework.instrument.classloading.LoadTimeWeaver
	 * @since 2.5
	 */
	String LOAD_TIME_WEAVER_BEAN_NAME = "loadTimeWeaver";

	/**
	 * 工厂中的 {@link Environment} bean 的名称。
	 *
	 * @since 3.1
	 */
	String ENVIRONMENT_BEAN_NAME = "environment";

	/**
	 * 工厂中的systemProperties bean 的名称。
	 *
	 * @see java.lang.System#getProperties()
	 */
	String SYSTEM_PROPERTIES_BEAN_NAME = "systemProperties";

	/**
	 * 工厂中的systemEnvironment bean 的名称。
	 *
	 * @see java.lang.System#getenv()
	 */
	String SYSTEM_ENVIRONMENT_BEAN_NAME = "systemEnvironment";

	/**
	 * 工厂中的 {@link ApplicationStartup} bean 的名称。
	 *
	 * @since 5.3
	 */
	String APPLICATION_STARTUP_BEAN_NAME = "applicationStartup";

	/**
	 * {@linkplain #registerShutdownHook() 关闭挂钩}线程的{@link Thread#getName() 名称}：{@value}。
	 *
	 * @see #registerShutdownHook()
	 * @since 5.2
	 */
	String SHUTDOWN_HOOK_THREAD_NAME = "SpringContextShutdownHook";


	/**
	 * 设置此应用程序上下文的唯一 id。
	 *
	 * @since 3.0
	 */
	void setId(String id);

	/**
	 * 设置此应用程序上下文的父级。
	 * <p>请注意，父级不应该被更改：它应该只在对象创建时在构造函数外部设置，
	 * 例如在 WebApplicationContext 设置时。
	 *
	 * @param parent 父上下文
	 * @see org.springframework.web.context.ConfigurableWebApplicationContext
	 */
	void setParent(@Nullable ApplicationContext parent);

	/**
	 * 设置此应用程序上下文的 {@code Environment}。
	 *
	 * @param environment 新环境
	 * @since 3.1
	 */
	void setEnvironment(ConfigurableEnvironment environment);

	/**
	 * 返回可配置的形式返回此应用程序上下文的环境，以便进一步自定义。
	 * form, allowing for further customization.
	 *
	 * @since 3.1
	 */
	@Override
	ConfigurableEnvironment getEnvironment();

	/**
	 * 为此应用程序上下文设置 ApplicationStartup。
	 * <p>这允许应用程序上下文在启动期间记录指标。
	 *
	 * @param applicationStartup 新的上下文事件工
	 * @param applicationStartup the new context event factory
	 * @since 5.3
	 */
	void setApplicationStartup(ApplicationStartup applicationStartup);

	/**
	 * 返回此应用程序上下文的 ApplicationStartup。
	 *
	 * @since 5.3
	 */
	ApplicationStartup getApplicationStartup();

	/**
	 * 添加一个新的 BeanFactoryPostProcessor，它将在刷新时应用于此应用程序上下文的内部 BeanFactory，
	 * 在任何 bean 定义得到评估之前。在上下文配置期间调用。
	 *
	 * @param postProcessor 要注册的工厂处理器
	 */
	void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor);

	/**
	 * 添加一个新的 ApplicationListener，它将在上下文事件（例如上下文刷新和上下文关闭）发生时得到通知。
	 * <p>请注意，此处注册的任何 ApplicationListener 将在上下文尚未激活时刷新，
	 * 或者在上下文已经激活的情况下，使用当前事件广播器实时应用。
	 *
	 * @param listener 要注册的 ApplicationListener
	 * @see org.springframework.context.event.ContextRefreshedEvent
	 * @see org.springframework.context.event.ContextClosedEvent
	 */
	void addApplicationListener(ApplicationListener<?> listener);

	/**
	 * 从此上下文的侦听器集中移除给定的 ApplicationListener，假设它之前是通过 {@link #addApplicationListener} 注册的。
	 *
	 * @param listener 要注销的 ApplicationListener
	 * @since 6.0
	 */
	void removeApplicationListener(ApplicationListener<?> listener);

	/**
	 * 指定用于加载类路径资源和 bean 类的类加载器。
	 * <p>此上下文类加载器将传递给内部 BeanFactory。
	 *
	 * @see org.springframework.core.io.DefaultResourceLoader#DefaultResourceLoader(ClassLoader)
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#setBeanClassLoader
	 * @since 5.2.7
	 */
	void setClassLoader(ClassLoader classLoader);

	/**
	 * 使用此应用程序上下文注册给定的协议解析器，允许处理其他资源协议。
	 * <p>任何此类解析器将在此上下文的标准解析规则之前被调用。因此，它也可以覆盖任何默认规则。
	 *
	 * @since 4.3
	 */
	void addProtocolResolver(ProtocolResolver resolver);

	/**
	 * 加载或刷新配置的持久表示形式，可能来自基于 Java 的配置、XML 文件、属性文件、关系数据库模式或其他格式。
	 * <p>由于这是一个启动方法，如果失败，它应该销毁已创建的所有单例，
	 * 以避免悬空资源。换句话说，调用此方法后，要么全部单例要么都不实例化。
	 *
	 * @throws BeansException        如果无法初始化 bean 工厂
	 * @throws IllegalStateException 如果已初始化且不支持多个刷新尝试
	 */
	void refresh() throws BeansException, IllegalStateException;

	/**
	 * 注册一个与 JVM 运行时绑定的关闭钩子，以便在 JVM 关闭时关闭此上下文，除非此时已经关闭。
	 * <p>此方法可以多次调用。每个上下文实例最多只会注册一个关闭钩子。
	 * <p>从 Spring Framework 5.2 开始，关闭钩子线程的{@linkplain Thread#getName() 名称}应该是{@link #SHUTDOWN_HOOK_THREAD_NAME}。
	 *
	 * @see java.lang.Runtime#addShutdownHook
	 * @see #close()
	 */
	void registerShutdownHook();

	/**
	 * 关闭此应用程序上下文，释放实现可能持有的所有资源和锁。这包括销毁所有缓存的单例bean。
	 * <p>注意：不会在父上下文上调用{@code close}；父上下文有自己独立的生命周期。
	 * <p>此方法可以多次调用而不会产生副作用：对已经关闭的上下文进行的后续{@code close}调用将被忽略。
	 */
	@Override
	void close();

	/**
	 * 确定此应用程序上下文是否处于活动状态，即是否至少已刷新一次且尚未关闭。
	 *
	 * @return 上下文是否仍处于活动状态
	 * @see #refresh()
	 * @see #close()
	 * @see #getBeanFactory()
	 */
	boolean isActive();

	/**
	 * 返回此应用程序上下文的内部 bean 工厂。可用于访问底层工厂的特定功能。
	 * <p>注意：不要使用此方法对 bean 工厂进行后处理；单例将在实例化之前已经被实例化。使用 BeanFactoryPostProcessor 在 bean 被触及之前拦截 BeanFactory 设置过程。
	 * <p>通常，此内部工厂仅在上下文处于活动状态时才可访问，即在{@link #refresh()}和{@link #close()}之间。{@link #isActive()}标志可用于检查上下文是否处于适当的状态。
	 *
	 * @return 底层 bean 工厂
	 * @throws IllegalStateException 如果上下文不持有内部 bean 工厂（通常如果尚未调用{@link #refresh()}或已经调用{@link #close()}）
	 * @see #isActive()
	 * @see #refresh()
	 * @see #close()
	 * @see #addBeanFactoryPostProcessor
	 */
	ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;

}

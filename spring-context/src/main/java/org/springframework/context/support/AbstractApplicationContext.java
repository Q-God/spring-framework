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

package org.springframework.context.support;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.support.ResourceEditorRegistrar;
import org.springframework.context.*;
import org.springframework.context.event.*;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.context.weaving.LoadTimeWeaverAware;
import org.springframework.context.weaving.LoadTimeWeaverAwareProcessor;
import org.springframework.core.NativeDetector;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link org.springframework.context.ApplicationContext} 接口的抽象实现。
 * 不强制要求用于配置的存储类型；仅实现常见的上下文功能。使用模板方法设计模式，要求具体的子类实现抽象方法。
 * 与普通的 BeanFactory 相比，ApplicationContext 应该能够检测到其内部 bean 工厂中定义的特殊 bean：
 * 因此，该类自动注册作为上下文中的 bean 定义的
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors}、
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessors} 和
 * {@link org.springframework.context.ApplicationListener ApplicationListeners}。
 * 一个 {@link org.springframework.context.MessageSource}
 * 也可以作为一个名为 "messageSource" 的 bean 在上下文中提供；否则，消息解析将委托给父上下文。
 * 此外，一个应用程序事件的多播器也可以作为上下文中的类型为 {@link org.springframework.context.event.ApplicationEventMulticaster} 的 "applicationEventMulticaster" bean 提供；
 * 否则，将使用类型为 {@link org.springframework.context.event.SimpleApplicationEventMulticaster} 的默认多播器。
 * 通过扩展 {@link org.springframework.core.io.DefaultResourceLoader} 来实现资源加载。
 * 因此，对非 URL 资源路径进行处理，将其视为类路径资源（支持包含包路径的完整类路径资源名称，例如 "mypackage/myresource.dat"），除非在子类中覆盖了 {@link #getResourceByPath} 方法。
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @author Brian Clozel
 * @see #refreshBeanFactory
 * @see #getBeanFactory
 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.ApplicationListener
 * @see org.springframework.context.MessageSource
 * @since January 21, 2001
 */
public abstract class AbstractApplicationContext extends DefaultResourceLoader
		implements ConfigurableApplicationContext {

	/**
	 * 在上下文中的 {@link MessageSource} bean 的名称。
	 * 如果未提供，则消息解析将委托给父级。
	 *
	 * @see org.springframework.context.MessageSource
	 * @see org.springframework.context.support.ResourceBundleMessageSource
	 * @see org.springframework.context.support.ReloadableResourceBundleMessageSource
	 * @see #getMessage(MessageSourceResolvable, Locale)
	 */
	public static final String MESSAGE_SOURCE_BEAN_NAME = "messageSource";

	/**
	 * 在上下文中的 {@link ApplicationEventMulticaster} bean 的名称。
	 * 如果未提供，则使用 {@link SimpleApplicationEventMulticaster}。
	 *
	 * @see org.springframework.context.event.ApplicationEventMulticaster
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 * @see #publishEvent(ApplicationEvent)
	 * @see #addApplicationListener(ApplicationListener)
	 */
	public static final String APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster";

	/**
	 * 在上下文中的 {@link LifecycleProcessor} bean 的名称。
	 * 如果未提供，则使用 {@link DefaultLifecycleProcessor}。
	 *
	 * @see org.springframework.context.LifecycleProcessor
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * @see #start()
	 * @see #stop()
	 * @since 3.0
	 */
	public static final String LIFECYCLE_PROCESSOR_BEAN_NAME = "lifecycleProcessor";


	static {
		// 提前加载 ContextClosedEvent 类，
		// 以避免在 WebLogic 8.1 中应用程序关闭时出现奇怪的类加载器问题。 (由 Dustin Woods 报告。)
		ContextClosedEvent.class.getName();
	}


	/**
	 * 此类使用的日志记录器。可供子类使用。
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * 此上下文的唯一标识符（如果有）。
	 */
	private String id = ObjectUtils.identityToString(this);

	/**
	 * 显示名称
	 */
	private String displayName = ObjectUtils.identityToString(this);

	/**
	 * 父上下文
	 */
	@Nullable
	private ApplicationContext parent;

	/**
	 * 此上下文使用的环境。
	 */
	@Nullable
	private ConfigurableEnvironment environment;

	/**
	 * 在刷新时应用的 BeanFactoryPostProcessors
	 */
	private final List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

	/**
	 * 此上下文启动时的系统时间（以毫秒为单位）.
	 */
	private long startupDate;

	/**
	 * 表示此上下文当前是否活动的标志。
	 */
	private final AtomicBoolean active = new AtomicBoolean();

	/**
	 * 表示此上下文是否已关闭的标志。.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * "刷新"和 "关闭" 的同步锁
	 */
	private final Lock startupShutdownLock = new ReentrantLock();

	/**
	 * 当前活动的启动/关闭线程。
	 */
	@Nullable
	private volatile Thread startupShutdownThread;

	/**
	 * 注册的 JVM 关闭挂钩的引用，如果已注册的话。
	 */
	@Nullable
	private Thread shutdownHook;

	/**
	 * 此上下文使用的 ResourcePatternResolver。
	 */
	private final ResourcePatternResolver resourcePatternResolver;

	/**
	 * 用于管理此上下文中 bean 生命周期的 LifecycleProcessor。
	 */
	@Nullable
	private LifecycleProcessor lifecycleProcessor;

	/**
	 * 我们将此接口的实现委托给的 MessageSource。
	 */
	@Nullable
	private MessageSource messageSource;

	/**
	 * 用于事件发布的辅助类
	 */
	@Nullable
	private ApplicationEventMulticaster applicationEventMulticaster;

	/**
	 * 应用程序启动指标.
	 **/
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/**
	 * 静态指定的监听器.
	 */
	private final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

	/**
	 * 刷新之前注册的本地监听器.
	 */
	@Nullable
	private Set<ApplicationListener<?>> earlyApplicationListeners;

	/**
	 * 在多播器设置之前发布的 ApplicationEvent。
	 */
	@Nullable
	private Set<ApplicationEvent> earlyApplicationEvents;


	/**
	 * 创建一个没有父上下文的新 AbstractApplicationContext。
	 */
	public AbstractApplicationContext() {
		this.resourcePatternResolver = getResourcePatternResolver();
	}

	/**
	 * 创建一个具有给定父上下文的新 AbstractApplicationContext。
	 *
	 * @param parent the parent context
	 */
	public AbstractApplicationContext(@Nullable ApplicationContext parent) {
		this();
		setParent(parent);
	}


	//---------------------------------------------------------------------
	// Implementation of ApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * 设置此应用程序上下文的唯一标识符。
	 * <p>默认情况下是上下文实例的对象标识符，或者如果上下文本身被定义为一个 bean，则是上下文 bean 的名称。
	 *
	 * @param id 上下文的唯一标识符
	 */
	@Override
	public void setId(String id) {
		this.id = id;
	}

	/**
	 * 获取上下文标识符
	 *
	 * @return
	 */
	@Override
	public String getId() {
		return this.id;
	}

	/**
	 * 获取ApplicationName 如子类未实现 默认返
	 * <p>
	 * 回""
	 *
	 * @return
	 */
	@Override
	public String getApplicationName() {
		return "";
	}

	/**
	 * 为此上下文设置一个友好名称。
	 * 通常在具体上下文实现的初始化期间完成。
	 * <p>默认情况下是上下文实例的对象标识符。
	 */
	public void setDisplayName(String displayName) {
		Assert.hasLength(displayName, "Display name must not be empty");
		this.displayName = displayName;
	}

	/**
	 * 返回此上下文的友好名称。
	 *
	 * @return 此上下文的显示名称（永远不会为 {@code null}）
	 */
	@Override
	public String getDisplayName() {
		return this.displayName;
	}

	/**
	 * 返回父上下文，如果没有父上下文则返回 {@code null}
	 * （也就是说，此上下文是上下文层次结构的根）。
	 */
	@Override
	@Nullable
	public ApplicationContext getParent() {
		return this.parent;
	}

	/**
	 * 设置此应用程序上下文的 {@code Environment}。
	 * <p>默认值由 {@link #createEnvironment()} 确定。用此方法替换默认值是一种选择，但应该
	 * 考虑通过 {@link #getEnvironment()} 进行配置。在任何情况下，这些修改都应该在 {@link #refresh()} 之前执行。
	 *
	 * @see org.springframework.context.support.AbstractApplicationContext#createEnvironment
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * 以可配置的形式返回此应用程序上下文的 {@code Environment}，允许进一步自定义。
	 * <p>如果未指定，则会通过 {@link #createEnvironment()} 初始化默认环境。
	 * {@link #createEnvironment()}.
	 */
	@Override
	public ConfigurableEnvironment getEnvironment() {
		//如果为空 通过createEnvironment()初始化默认环境
		if (this.environment == null) {
			this.environment = createEnvironment();
		}
		return this.environment;
	}

	/**
	 * 创建并返回一个新的 {@link StandardEnvironment}。
	 * <p>子类可以重写此方法以提供自定义的 {@link ConfigurableEnvironment} 实现。
	 */
	protected ConfigurableEnvironment createEnvironment() {
		return new StandardEnvironment();
	}

	/**
	 * 返回此上下文的内部 bean 工厂作为 AutowireCapableBeanFactory，如果已经可用。
	 *
	 * @see #getBeanFactory()
	 */
	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		return getBeanFactory();
	}

	/**
	 * 返回此上下文首次加载时的时间戳（毫秒）。
	 */
	@Override
	public long getStartupDate() {
		return this.startupDate;
	}

	/**
	 * 将给定事件发布到所有监听器。
	 * <p>注意：监听器在 MessageSource 之后初始化，以便能够在监听器实现中访问它。
	 * 因此，MessageSource 实现不能发布事件。
	 *
	 * @param event 要发布的事件（可能是特定于应用程序的或标准框架事件）
	 */
	@Override
	public void publishEvent(ApplicationEvent event) {
		publishEvent(event, null);
	}

	/**
	 * 将给定事件发布到所有监听器。
	 * <p>注意：监听器在 MessageSource 之后初始化，以便能够在监听器实现中访问它。
	 * 因此，MessageSource 实现不能发布事件。
	 *
	 * @param event 要发布的事件（可能是 {@link ApplicationEvent}
	 *              或要转换为 {@link PayloadApplicationEvent} 的负载对象）
	 */
	@Override
	public void publishEvent(Object event) {
		publishEvent(event, null);
	}

	/**
	 * 将给定事件发布到所有监听器。
	 * <p>这是所有其他 {@code publishEvent} 方法都引用的内部委托。不应直接调用它，
	 * 而应作为层次结构中应用程序上下文之间的传播机制，可能在子类中进行自定义传播安排时覆盖。
	 *
	 * @param event    要发布的事件（可能是 {@link ApplicationEvent} 或要转换为 {@link PayloadApplicationEvent} 的负载对象）
	 * @param typeHint 已解析的事件类型（如果已知）。
	 *                 此方法的实现还容忍用于要转换为 {@link PayloadApplicationEvent} 的负载对象的负载类型提示。
	 *                 但是，对于这种情况，建议通过 {@link PayloadApplicationEvent#PayloadApplicationEvent(Object, Object, ResolvableType)}
	 *                 构造一个实际的事件对象。
	 * @see ApplicationEventMulticaster#multicastEvent(ApplicationEvent, ResolvableType)
	 * @since 4.2
	 */
	protected void publishEvent(Object event, @Nullable ResolvableType typeHint) {
		//断言事件不能为空
		Assert.notNull(event, "Event must not be null");

		ResolvableType eventType = null;


		// 如果需要，将事件装饰为 ApplicationEvent
		ApplicationEvent applicationEvent;
		if (event instanceof ApplicationEvent applEvent) {
			applicationEvent = applEvent;
			eventType = typeHint;
		} else {
			ResolvableType payloadType = null;
			if (typeHint != null && ApplicationEvent.class.isAssignableFrom(typeHint.toClass())) {
				eventType = typeHint;
			} else {
				payloadType = typeHint;
			}
			applicationEvent = new PayloadApplicationEvent<>(this, event, payloadType);
		}

		// Determine event type only once (for multicast and parent publish)
		if (eventType == null) {
			eventType = ResolvableType.forInstance(applicationEvent);
			if (typeHint == null) {
				typeHint = eventType;
			}
		}

		// Multicast right now if possible - or lazily once the multicaster is initialized
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		} else if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);
		}

		// Publish event via parent context as well...
		if (this.parent != null) {
			if (this.parent instanceof AbstractApplicationContext abstractApplicationContext) {
				abstractApplicationContext.publishEvent(event, typeHint);
			} else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * Return the internal ApplicationEventMulticaster used by the context.
	 *
	 * @return the internal ApplicationEventMulticaster (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		return this.applicationEventMulticaster;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "ApplicationStartup must not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	/**
	 * Return the internal LifecycleProcessor used by the context.
	 *
	 * @return the internal LifecycleProcessor (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		return this.lifecycleProcessor;
	}

	/**
	 * Return the ResourcePatternResolver to use for resolving location patterns
	 * into Resource instances. Default is a
	 * {@link org.springframework.core.io.support.PathMatchingResourcePatternResolver},
	 * supporting Ant-style location patterns.
	 * <p>Can be overridden in subclasses, for extended resolution strategies,
	 * for example in a web environment.
	 * <p><b>Do not call this when needing to resolve a location pattern.</b>
	 * Call the context's {@code getResources} method instead, which
	 * will delegate to the ResourcePatternResolver.
	 *
	 * @return the ResourcePatternResolver for this context
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableApplicationContext interface
	//---------------------------------------------------------------------

	/**
	 * Set the parent of this application context.
	 * <p>The parent {@linkplain ApplicationContext#getEnvironment() environment} is
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) merged} with
	 * this (child) application context environment if the parent is non-{@code null} and
	 * its environment is an instance of {@link ConfigurableEnvironment}.
	 *
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		if (parent != null) {
			Environment parentEnvironment = parent.getEnvironment();
			if (parentEnvironment instanceof ConfigurableEnvironment configurableEnvironment) {
				getEnvironment().merge(configurableEnvironment);
			}
		}
	}

	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * Return the list of BeanFactoryPostProcessors that will get applied
	 * to the internal BeanFactory.
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		return this.beanFactoryPostProcessors;
	}

	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		this.applicationListeners.add(listener);
	}

	@Override
	public void removeApplicationListener(ApplicationListener<?> listener) {
		Assert.notNull(listener, "ApplicationListener must not be null");
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.removeApplicationListener(listener);
		}
		this.applicationListeners.remove(listener);
	}

	/**
	 * Return the list of statically specified ApplicationListeners.
	 */
	public Collection<ApplicationListener<?>> getApplicationListeners() {
		return this.applicationListeners;
	}

	/**
	 * 刷新该应用程序上下文，将其状态更新为最新。
	 *
	 * @throws BeansException        如果在刷新过程中发生 Bean 异常
	 * @throws IllegalStateException 如果上下文已经启动或处于关闭状态
	 */
	@Override
	public void refresh() throws BeansException, IllegalStateException {
		// 加锁，以确保线程安全
		this.startupShutdownLock.lock();
		try {
			// 当前线程标记
			this.startupShutdownThread = Thread.currentThread();

			// 开始跟踪刷新上下文的步骤
			StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");

			// 上下文刷新前准备工作
			prepareRefresh();

			// 告诉子类刷新内部 bean 工厂
			ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

			//为在此上下文中使用的 bean 工厂做准备.
			prepareBeanFactory(beanFactory);

			try {
				// 允许在上下文子类中对 bean 工厂进行后处理.
				postProcessBeanFactory(beanFactory);

				// 开始跟踪 bean 后置处理的步骤
				StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");
				// 调用在上下文中注册为 bean 的工厂处理器
				invokeBeanFactoryPostProcessors(beanFactory);
				// 注册拦截 bean 创建的 bean 处理器。
				registerBeanPostProcessors(beanFactory);
				//结束跟踪 bean 后置处理的步骤
				beanPostProcess.end();

				//初始化此上下文的消息源
				initMessageSource();

				//初始化此上下文的事件多播器.
				initApplicationEventMulticaster();

				//初始化特定上下文子类中的其他特殊 bean.
				onRefresh();

				// 检查监听器 bean 并注册它们.
				registerListeners();

				// 实例化所有剩余的（非懒加载）单例 bean.
				finishBeanFactoryInitialization(beanFactory);

				//  最后一步：发布相应的事件.
				finishRefresh();
			} catch (RuntimeException | Error ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Exception encountered during context initialization - " +
							"cancelling refresh attempt: " + ex);
				}

				//销毁已创建的单例，以避免悬空资源
				destroyBeans();

				//重置 'active' 标志
				cancelRefresh(ex);

				// 将异常传播给调用者.
				throw ex;
			} finally {
				//结束跟踪刷新上下文的步骤
				contextRefresh.end();
			}
		} finally {
			//清除线程标记
			this.startupShutdownThread = null;
			//解锁关闭过程
			this.startupShutdownLock.unlock();
		}
	}

	/**
	 * 这段代码是准备上下文以进行刷新的方法。以下是方法中的各个步骤：
	 * <p>
	 * 设置启动日期和活动标志： 将上下文的启动日期设置为当前时间戳，将关闭标志设为 false（表示上下文处于打开状态），将活动标志设为 true（表示上下文处于活动状态）。
	 * <p>
	 * 输出刷新日志： 如果日志级别为 DEBUG，则输出刷新日志。如果 TRACE 级别被启用，则输出包含该上下文引用的 TRACE 日志，否则输出 DEBUG 日志。
	 * <p>
	 * 初始化属性源： 调用 initPropertySources() 方法，初始化上下文环境中的任何占位符属性源。这个步骤用于解析属性文件中的占位符属性值。
	 * <p>
	 * 验证必需属性： 调用 getEnvironment().validateRequiredProperties() 方法，验证所有在环境中标记为必需的属性是否都被解析。这一步骤确保应用程序所需的所有属性都已正确设置。
	 * <p>
	 * 存储刷新前的 ApplicationListeners： 如果 earlyApplicationListeners 为空，则将当前的应用程序监听器列表复制到 earlyApplicationListeners 中，否则清空当前的应用程序监听器列表，并将 earlyApplicationListeners 中的监听器重新添加到当前列表中。
	 * <p>
	 * 收集早期的 ApplicationEvents： 初始化一个早期应用程序事件集合 earlyApplicationEvents，以便在多播器可用后发布这些事件。
	 * <p>
	 * 准备该上下文以进行刷新，设置其启动日期和活动标志，以及执行属性源的任何初始化
	 */
	protected void prepareRefresh() {
		// 切换到活动状态
		// 设置上下文的启动时间为当前时间戳
		this.startupDate = System.currentTimeMillis();
		// 将关闭标志设为 false，表示上下文处于打开状态
		this.closed.set(false);
		// 将活动标志设为 true，表示上下文处于活动状态
		this.active.set(true);

		// 如果日志级别为 DEBUG，则输出刷新日志
		if (logger.isDebugEnabled()) {
			// 如果日志级别为 TRACE，则输出刷新日志
			if (logger.isTraceEnabled()) {
				// 输出 TRACE 级别的刷新日志
				logger.trace("Refreshing " + this);
			} else {
				// 输出 DEBUG 级别的刷新日志
				logger.debug("Refreshing " + getDisplayName());
			}
		}

		// / 初始化上下文环境中的任何占位符属性源。
		initPropertySources();

		//验证所有标记为必需的属性是否可解析：
		// 参见 ConfigurablePropertyResolver#setRequiredProperties
		getEnvironment().validateRequiredProperties();

		//  存储刷新前的 ApplicationListeners...
		if (this.earlyApplicationListeners == null) {
			//创建存储刷新前ApplicationListeners容器
			this.earlyApplicationListeners = new LinkedHashSet<>(this.applicationListeners);
		} else {
			// 将本地应用程序监听器重置为刷新前的状态。
			// 清空当前的应用程序监听器列表
			this.applicationListeners.clear();
			// 重新添加刷新前的应用程序监听器列表
			this.applicationListeners.addAll(this.earlyApplicationListeners);
		}


		// 允许收集早期的 ApplicationEvents，
		// 一旦多播器可用，就会发布它们...
		// 初始化早期应用程序事件集合
		this.earlyApplicationEvents = new LinkedHashSet<>();
	}

	/**
	 * <p>用实际实例替换任何存根属性源。
	 * <p>在子类中，通常可以通过覆盖此方法来初始化属性源。
	 * * 例如，在 Web 应用程序上下文中，可以使用 {@link org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources} 方法来初始化属性源。
	 *
	 * @see org.springframework.core.env.PropertySource.StubPropertySource
	 * @see org.springframework.web.context.support.WebApplicationContextUtils#initServletPropertySources
	 */
	protected void initPropertySources() {
		// 对于子类：默认情况下不执行任何操作。
	}

	/**
	 * 告知子类刷新内部的 bean 工厂。
	 *
	 * @return 刷新后的 BeanFactory 实例
	 * @see #refreshBeanFactory()
	 * @see #getBeanFactory()
	 */
	protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
		// 调用 refreshBeanFactory() 方法刷新内部的 bean 工厂
		refreshBeanFactory();
		// 返回刷新后的 BeanFactory 实例
		return getBeanFactory();
	}

	/**
	 * 设置工厂的类加载器和 Bean 表达式解析器。
	 * 注册属性编辑器注册器。
	 * 配置工厂使用上下文回调。
	 * 注册可解析的依赖项和早期后处理器。
	 * 检测是否存在 LoadTimeWeaver 并准备进行编织。
	 * 注册默认环境 bean，包括 Environment、系统属性和系统环境等。
	 * 配置工厂的标准上下文特性，例如上下文的类加载器和后处理器。
	 *
	 * @param beanFactory 要配置的 BeanFactory
	 */
	protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 告知内部 bean 工厂使用上下文的类加载器等。
		// 设置工厂使用上下文的类加载器
		beanFactory.setBeanClassLoader(getClassLoader());
		// 设置 Bean 表达式解析器
		beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(beanFactory.getBeanClassLoader()));
		// 注册属性编辑器注册器
		beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));

		// 使用上下文回调配置 bean 工厂。
		// 添加 ApplicationContextAware 后处理器
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
		// 忽略 EnvironmentAware 接口
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
		// 忽略 EmbeddedValueResolverAware 接口
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		// 忽略 ResourceLoaderAware 接口
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		// 忽略 ApplicationEventPublisherAware 接口
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		// 忽略 MessageSourceAware 接口
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		// 忽略 ApplicationContextAware 接口
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);
		// 忽略 ApplicationStartupAware 接口
		beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);

		// 在普通工厂中不将 BeanFactory 接口注册为可解析的类型。
		// 将 MessageSource 注册（并在自动装配时找到）为一个 bean。
		// 注册 BeanFactory 为可解析的依赖项
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
		// 注册 ResourceLoader 为可解析的依赖项
		beanFactory.registerResolvableDependency(ResourceLoader.class, this);
		// 注册 ApplicationEventPublisher 为可解析的依赖项
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
		// 注册 ApplicationContext 为可解析的依赖项
		beanFactory.registerResolvableDependency(ApplicationContext.class, this);

		// 注册用于检测内部 bean 是否为 ApplicationListener 的早期后置处理器
		// 添加用于检测内部 bean 是否为 ApplicationListener 的后置处理器
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));

		// 检测是否存在 LoadTimeWeaver 并准备进行编织（如果找到）
		if (!NativeDetector.inNativeImage() && beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			// 添加 LoadTimeWeaverAwareProcessor
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			// 设置临时的 ClassLoader 用于类型匹配
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}

		// 注册默认环境 bean。
		if (!beanFactory.containsLocalBean(ENVIRONMENT_BEAN_NAME)) {
			//注册 Environment bean
			beanFactory.registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment());
		}
		if (!beanFactory.containsLocalBean(SYSTEM_PROPERTIES_BEAN_NAME)) {
			// 注册 Environment bean
			beanFactory.registerSingleton(SYSTEM_PROPERTIES_BEAN_NAME, getEnvironment().getSystemProperties());
		}
		if (!beanFactory.containsLocalBean(SYSTEM_ENVIRONMENT_BEAN_NAME)) {
			// 注册系统环境 bean
			beanFactory.registerSingleton(SYSTEM_ENVIRONMENT_BEAN_NAME, getEnvironment().getSystemEnvironment());
		}
		if (!beanFactory.containsLocalBean(APPLICATION_STARTUP_BEAN_NAME)) {
			// 注册应用启动器 bean
			beanFactory.registerSingleton(APPLICATION_STARTUP_BEAN_NAME, getApplicationStartup());
		}
	}

	/**
	 * 在标准初始化后修改应用程序上下文的内部Bean工厂。初始定义资源将已加载，
	 * 但尚未运行任何后处理器，也未注册任何派生Bean定义，
	 * 最重要的是，还未实例化任何Bean。
	 * <p>这个模板方法允许在某些 AbstractApplicationContext 子类中注册特殊的 BeanPostProcessor 等。
	 *
	 * @param beanFactory 应用程序上下文使用的Bean工厂
	 */
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	}

	/**
	 * Instantiate and invoke all registered BeanFactoryPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before singleton instantiation.
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

		// Detect a LoadTimeWeaver and prepare for weaving, if found in the meantime
		// (e.g. through an @Bean method registered by ConfigurationClassPostProcessor)
		if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null &&
				beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}
	}

	/**
	 * Instantiate and register all BeanPostProcessor beans,
	 * respecting explicit order if given.
	 * <p>Must be called before any instantiation of application beans.
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this);
	}

	/**
	 * Initialize the {@link MessageSource}.
	 * <p>Uses parent's {@code MessageSource} if none defined in this context.
	 *
	 * @see #MESSAGE_SOURCE_BEAN_NAME
	 */
	protected void initMessageSource() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);
			// Make MessageSource aware of parent MessageSource.
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource hms &&
					hms.getParentMessageSource() == null) {
				// Only set parent context as parent MessageSource if no parent MessageSource
				// registered already.
				hms.setParentMessageSource(getInternalParentMessageSource());
			}
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		} else {
			// Use empty MessageSource to be able to accept getMessage calls.
			DelegatingMessageSource dms = new DelegatingMessageSource();
			dms.setParentMessageSource(getInternalParentMessageSource());
			this.messageSource = dms;
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * Initialize the {@link ApplicationEventMulticaster}.
	 * <p>Uses {@link SimpleApplicationEventMulticaster} if none defined in the context.
	 *
	 * @see #APPLICATION_EVENT_MULTICASTER_BEAN_NAME
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			this.applicationEventMulticaster =
					beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		} else {
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * Initialize the {@link LifecycleProcessor}.
	 * <p>Uses {@link DefaultLifecycleProcessor} if none defined in the context.
	 *
	 * @see #LIFECYCLE_PROCESSOR_BEAN_NAME
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * @since 3.0
	 */
	protected void initLifecycleProcessor() {
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			this.lifecycleProcessor = beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		} else {
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + LIFECYCLE_PROCESSOR_BEAN_NAME + "' bean, using " +
						"[" + this.lifecycleProcessor.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * 模板方法，可以被覆盖以添加特定于上下文的刷新工作。
	 * 在特殊 Bean 初始化之前调用，但在单例实例化之前。
	 * <p>这个实现为空。
	 *
	 * @throws BeansException 如果发生错误
	 * @see #refresh()
	 */
	protected void onRefresh() throws BeansException {
		// 对于子类：默认情况下不执行任何操作。
	}

	/**
	 * 将实现 ApplicationListener 接口的 Bean 添加为监听器。
	 * 不影响其他监听器，其他监听器可以在不作为 Bean 的情况下添加。
	 */
	protected void registerListeners() {
		// 首先注册静态指定的监听器
		for (ApplicationListener<?> listener : getApplicationListeners()) {
			getApplicationEventMulticaster().addApplicationListener(listener);
		}

		// 不要在这里初始化 FactoryBeans：我们需要保持所有常规 Bean 未初始化，
		// 以便让后处理器应用到它们！
		String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false);
		for (String listenerBeanName : listenerBeanNames) {
			getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
		}

		// 现在我们终于有了一个多播器，可以发布早期的应用程序事件了...
		Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
		this.earlyApplicationEvents = null;
		if (!CollectionUtils.isEmpty(earlyEventsToProcess)) {
			for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
				getApplicationEventMulticaster().multicastEvent(earlyEvent);
			}
		}
	}

	/**
	 * 完成此上下文的 Bean 工厂初始化，初始化所有剩余的单例 Bean。
	 */
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		// 初始化此上下文的引导执行器。
		if (beanFactory.containsBean(BOOTSTRAP_EXECUTOR_BEAN_NAME) &&
				beanFactory.isTypeMatch(BOOTSTRAP_EXECUTOR_BEAN_NAME, Executor.class)) {
			beanFactory.setBootstrapExecutor(
					beanFactory.getBean(BOOTSTRAP_EXECUTOR_BEAN_NAME, Executor.class));
		}

		// 初始化此上下文的转换服务。
		if (beanFactory.containsBean(CONVERSION_SERVICE_BEAN_NAME) &&
				beanFactory.isTypeMatch(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class)) {
			beanFactory.setConversionService(
					beanFactory.getBean(CONVERSION_SERVICE_BEAN_NAME, ConversionService.class));
		}

		// 如果之前没有注册任何 BeanFactoryPostProcessor（例如 PropertySourcesPlaceholderConfigurer Bean），则注册默认的嵌入式值解析器：
		// 此时，主要用于在注解属性值中进行解析。
		if (!beanFactory.hasEmbeddedValueResolver()) {
			beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
		}

		// 提前初始化 LoadTimeWeaverAware Bean，以便早期注册它们的转换器。
		String[] weaverAwareNames = beanFactory.getBeanNamesForType(LoadTimeWeaverAware.class, false, false);
		for (String weaverAwareName : weaverAwareNames) {
			getBean(weaverAwareName);
		}

		// 停止使用临时的 ClassLoader 进行类型匹配。
		beanFactory.setTempClassLoader(null);

		// 允许缓存所有 Bean 定义元数据，不期望进一步的更改。
		beanFactory.freezeConfiguration();

		// 实例化所有剩余的（非延迟初始化）单例。
		beanFactory.preInstantiateSingletons();
	}

	/**
	 * Finish the refresh of this context, invoking the LifecycleProcessor's
	 * onRefresh() method and publishing the
	 * {@link org.springframework.context.event.ContextRefreshedEvent}.
	 */
	protected void finishRefresh() {
		// Reset common introspection caches in Spring's core infrastructure.
		resetCommonCaches();

		// Clear context-level resource caches (such as ASM metadata from scanning).
		clearResourceCaches();

		// Initialize lifecycle processor for this context.
		initLifecycleProcessor();

		// Propagate refresh to lifecycle processor first.
		getLifecycleProcessor().onRefresh();

		// Publish the final event.
		publishEvent(new ContextRefreshedEvent(this));
	}

	/**
	 * Cancel this context's refresh attempt, resetting the {@code active} flag
	 * after an exception got thrown.
	 *
	 * @param ex the exception that led to the cancellation
	 */
	protected void cancelRefresh(Throwable ex) {
		this.active.set(false);

		// Reset common introspection caches in Spring's core infrastructure.
		resetCommonCaches();
	}

	/**
	 * Reset Spring's common reflection metadata caches, in particular the
	 * {@link ReflectionUtils}, {@link AnnotationUtils}, {@link ResolvableType}
	 * and {@link CachedIntrospectionResults} caches.
	 *
	 * @see ReflectionUtils#clearCache()
	 * @see AnnotationUtils#clearCache()
	 * @see ResolvableType#clearCache()
	 * @see CachedIntrospectionResults#clearClassLoader(ClassLoader)
	 * @since 4.2
	 */
	protected void resetCommonCaches() {
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		ResolvableType.clearCache();
		CachedIntrospectionResults.clearClassLoader(getClassLoader());
	}

	@Override
	public void clearResourceCaches() {
		super.clearResourceCaches();
		if (this.resourcePatternResolver instanceof PathMatchingResourcePatternResolver pmrpr) {
			pmrpr.clearCache();
		}
	}


	/**
	 * Register a shutdown hook {@linkplain Thread#getName() named}
	 * {@code SpringContextShutdownHook} with the JVM runtime, closing this
	 * context on JVM shutdown unless it has already been closed at that time.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 *
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					if (isStartupShutdownThreadStuck()) {
						active.set(false);
						return;
					}
					startupShutdownLock.lock();
					try {
						doClose();
					} finally {
						startupShutdownLock.unlock();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * Determine whether an active startup/shutdown thread is currently stuck,
	 * e.g. through a {@code System.exit} call in a user component.
	 */
	private boolean isStartupShutdownThreadStuck() {
		Thread activeThread = this.startupShutdownThread;
		if (activeThread != null && activeThread.getState() == Thread.State.WAITING) {
			// Indefinitely waiting: might be Thread.join or the like, or System.exit
			activeThread.interrupt();
			try {
				// Leave just a little bit of time for the interruption to show effect
				Thread.sleep(1);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			if (activeThread.getState() == Thread.State.WAITING) {
				// Interrupted but still waiting: very likely a System.exit call
				return true;
			}
		}
		return false;
	}

	/**
	 * Close this application context, destroying all beans in its bean factory.
	 * <p>Delegates to {@code doClose()} for the actual closing procedure.
	 * Also removes a JVM shutdown hook, if registered, as it's not needed anymore.
	 *
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		if (isStartupShutdownThreadStuck()) {
			this.active.set(false);
			return;
		}

		this.startupShutdownLock.lock();
		try {
			this.startupShutdownThread = Thread.currentThread();

			doClose();

			// If we registered a JVM shutdown hook, we don't need it anymore now:
			// We've already explicitly closed the context.
			if (this.shutdownHook != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				} catch (IllegalStateException ex) {
					// ignore - VM is already shutting down
				}
			}
		} finally {
			this.startupShutdownThread = null;
			this.startupShutdownLock.unlock();
		}
	}

	/**
	 * Actually performs context closing: publishes a ContextClosedEvent and
	 * destroys the singletons in the bean factory of this application context.
	 * <p>Called by both {@code close()} and a JVM shutdown hook, if any.
	 *
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	protected void doClose() {
		// Check whether an actual close attempt is necessary...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			try {
				// Publish shutdown event.
				publishEvent(new ContextClosedEvent(this));
			} catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// Stop all Lifecycle beans, to avoid delays during individual destruction.
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				} catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// Destroy all cached singletons in the context's BeanFactory.
			destroyBeans();

			// Close the state of this context itself.
			closeBeanFactory();

			// Let subclasses do some final clean-up if they wish...
			onClose();

			// Reset common introspection caches to avoid class reference leaks.
			resetCommonCaches();

			// Reset local application listeners to pre-refresh state.
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// Reset internal delegates.
			this.applicationEventMulticaster = null;
			this.messageSource = null;
			this.lifecycleProcessor = null;

			// Switch to inactive.
			this.active.set(false);
		}
	}

	/**
	 * Template method for destroying all beans that this context manages.
	 * The default implementation destroy all cached singletons in this context,
	 * invoking {@code DisposableBean.destroy()} and/or the specified
	 * "destroy-method".
	 * <p>Can be overridden to add context-specific bean destruction steps
	 * right before or right after standard singleton destruction,
	 * while the context's BeanFactory is still active.
	 *
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		getBeanFactory().destroySingletons();
	}

	/**
	 * Template method which can be overridden to add context-specific shutdown work.
	 * The default implementation is empty.
	 * <p>Called at the end of {@link #doClose}'s shutdown procedure, after
	 * this context's BeanFactory has been closed. If custom shutdown logic
	 * needs to execute while the BeanFactory is still active, override
	 * the {@link #destroyBeans()} method instead.
	 */
	protected void onClose() {
		// For subclasses: do nothing by default.
	}

	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * Assert that this context's BeanFactory is currently active,
	 * throwing an {@link IllegalStateException} if it isn't.
	 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
	 * on an active context, i.e. in particular all bean accessor methods.
	 * <p>The default implementation checks the {@link #isActive() 'active'} status
	 * of this context overall. May be overridden for more specific checks, or for a
	 * no-op if {@link #getBeanFactory()} itself throws an exception in such a case.
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			} else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, requiredType);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(name, args);
	}

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType);
	}

	@Override
	public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBean(requiredType, args);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType);
	}

	@Override
	public boolean containsBean(String name) {
		return getBeanFactory().containsBean(name);
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isSingleton(name);
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isPrototype(name);
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		assertBeanFactoryActive();
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	@Override
	public String[] getAliases(String name) {
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit);
	}

	@Override
	public <A extends Annotation> Set<A> findAllAnnotationsOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAllAnnotationsOnBean(beanName, annotationType, allowFactoryBeanInit);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return getParent();
	}

	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * Return the internal bean factory of the parent context if it implements
	 * ConfigurableApplicationContext; else, return the parent context itself.
	 *
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	@Nullable
	protected BeanFactory getInternalParentBeanFactory() {
		return (getParent() instanceof ConfigurableApplicationContext cac ?
				cac.getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of MessageSource interface
	//---------------------------------------------------------------------

	@Override
	public String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	@Override
	public String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * Return the internal MessageSource used by the context.
	 *
	 * @return the internal MessageSource (never {@code null})
	 * @throws IllegalStateException if the context has not been initialized yet
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		return this.messageSource;
	}

	/**
	 * Return the internal message source of the parent context if it is an
	 * AbstractApplicationContext too; else, return the parent context itself.
	 */
	@Nullable
	protected MessageSource getInternalParentMessageSource() {
		return (getParent() instanceof AbstractApplicationContext abstractApplicationContext ?
				abstractApplicationContext.messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// Implementation of ResourcePatternResolver interface
	//---------------------------------------------------------------------

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Implementation of Lifecycle interface
	//---------------------------------------------------------------------

	@Override
	public void start() {
		getLifecycleProcessor().start();
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		getLifecycleProcessor().stop();
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public boolean isRunning() {
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// Abstract methods that must be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Subclasses must implement this method to perform the actual configuration load.
	 * The method is invoked by {@link #refresh()} before any other initialization work.
	 * <p>A subclass will either create a new bean factory and hold a reference to it,
	 * or return a single BeanFactory instance that it holds. In the latter case, it will
	 * usually throw an IllegalStateException if refreshing the context more than once.
	 *
	 * @throws BeansException        if initialization of the bean factory failed
	 * @throws IllegalStateException if already initialized and multiple refresh
	 *                               attempts are not supported
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * Subclasses must implement this method to release their internal bean factory.
	 * This method gets invoked by {@link #close()} after all other shutdown work.
	 * <p>Should never throw an exception but rather log shutdown failures.
	 */
	protected abstract void closeBeanFactory();

	/**
	 * Subclasses must return their internal bean factory here. They should implement the
	 * lookup efficiently, so that it can be called repeatedly without a performance penalty.
	 * <p>Note: Subclasses should check whether the context is still active before
	 * returning the internal bean factory. The internal factory should generally be
	 * considered unavailable once the context has been closed.
	 *
	 * @return this application context's internal bean factory (never {@code null})
	 * @throws IllegalStateException if the context does not hold an internal bean factory yet
	 *                               (usually if {@link #refresh()} has never been called) or if the context has been
	 *                               closed already
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * Return information about this context.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(getDisplayName());
		sb.append(", started on ").append(new Date(getStartupDate()));
		ApplicationContext parent = getParent();
		if (parent != null) {
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		return sb.toString();
	}

}

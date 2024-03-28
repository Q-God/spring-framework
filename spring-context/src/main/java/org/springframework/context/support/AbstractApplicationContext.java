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
			// 如果事件不是 ApplicationEvent 类型，则创建一个 PayloadApplicationEvent
			ResolvableType payloadType = null;
			if (typeHint != null && ApplicationEvent.class.isAssignableFrom(typeHint.toClass())) {
				eventType = typeHint;
			} else {
				payloadType = typeHint;
			}
			applicationEvent = new PayloadApplicationEvent<>(this, event, payloadType);
		}

		// 仅在需要时确定事件类型（用于多播和父级发布）
		if (eventType == null) {
			eventType = ResolvableType.forInstance(applicationEvent);
			if (typeHint == null) {
				typeHint = eventType;
			}
		}

		// 如果早期应用程序事件列表不为空，则将事件添加到列表中
		if (this.earlyApplicationEvents != null) {
			this.earlyApplicationEvents.add(applicationEvent);
		}
		// 否则，如果 applicationEventMulticaster 不为空，则进行多播事件
		else if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);
		}

		// 通过父上下文也发布事件
		if (this.parent != null) {
			// 如果父上下文是 AbstractApplicationContext，则调用其 publishEvent 方法
			if (this.parent instanceof AbstractApplicationContext abstractApplicationContext) {
				abstractApplicationContext.publishEvent(event, typeHint);
			}
			// 否则，直接调用父上下文的 publishEvent 方法
			else {
				this.parent.publishEvent(event);
			}
		}
	}

	/**
	 * 返回上下文使用的内部 ApplicationEventMulticaster。
	 *
	 * @return 内部的 ApplicationEventMulticaster（永远不会为 {@code null}）
	 * @throws IllegalStateException 如果上下文尚未初始化
	 */
	ApplicationEventMulticaster getApplicationEventMulticaster() throws IllegalStateException {
		// 如果 applicationEventMulticaster 为空，则抛出 IllegalStateException
		if (this.applicationEventMulticaster == null) {
			throw new IllegalStateException("ApplicationEventMulticaster not initialized - " +
					"call 'refresh' before multicasting events via the context: " + this);
		}
		// 返回 applicationEventMulticaster
		return this.applicationEventMulticaster;
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		// 断言 applicationStartup 不为空
		Assert.notNull(applicationStartup, "ApplicationStartup must not be null");
		// 设置 applicationStartup
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		// 返回 applicationStartup
		return this.applicationStartup;
	}

	/**
	 * 返回上下文使用的内部 LifecycleProcessor。
	 *
	 * @return 内部的 LifecycleProcessor（永远不会为 {@code null}）
	 * @throws IllegalStateException 如果上下文尚未初始化
	 */
	LifecycleProcessor getLifecycleProcessor() throws IllegalStateException {
		// 如果 lifecycleProcessor 为空，则抛出 IllegalStateException
		if (this.lifecycleProcessor == null) {
			throw new IllegalStateException("LifecycleProcessor not initialized - " +
					"call 'refresh' before invoking lifecycle methods via the context: " + this);
		}
		// 返回 lifecycleProcessor
		return this.lifecycleProcessor;
	}

	/**
	 * 返回用于将位置模式解析为资源实例的 ResourcePatternResolver。
	 * 默认值是一个支持 Ant 样式位置模式的 PathMatchingResourcePatternResolver。
	 * <p>可以在子类中重写，以扩展解析策略，例如在 Web 环境中。
	 * <p><b>需要解析位置模式时，请不要调用此方法。</b>
	 * 相反，请调用上下文的 {@code getResources} 方法，该方法将委托给 ResourcePatternResolver。
	 *
	 * @return 此上下文的 ResourcePatternResolver
	 * @see #getResources
	 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
	 */
	protected ResourcePatternResolver getResourcePatternResolver() {
		// 创建一个 PathMatchingResourcePatternResolver，并传入当前 ApplicationContext 对象
		return new PathMatchingResourcePatternResolver(this);
	}


	//---------------------------------------------------------------------
	// ConfigurableApplicationContext 接口的实现
	//---------------------------------------------------------------------

	/**
	 * 设置此应用程序上下文的父级。
	 * <p>如果父级不为 {@code null}，并且其环境是 {@link ConfigurableEnvironment} 的实例，
	 * 则父级 {@linkplain ApplicationContext#getEnvironment() environment} 会与此（子级）应用程序上下文的环境
	 * {@linkplain ConfigurableEnvironment#merge(ConfigurableEnvironment) 合并}。
	 *
	 * @see ConfigurableEnvironment#merge(ConfigurableEnvironment)
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		this.parent = parent;
		// 如果父级不为 null
		if (parent != null) {
			// 获取父级 ApplicationContext 的环境
			Environment parentEnvironment = parent.getEnvironment();
			// 如果父级环境是 ConfigurableEnvironment 的实例
			if (parentEnvironment instanceof ConfigurableEnvironment configurableEnvironment) {
				// 将父级环境与当前环境进行合并
				getEnvironment().merge(configurableEnvironment);
			}
		}
	}

	/**
	 * 添加一个 BeanFactoryPostProcessor 到内部 BeanFactory 中。
	 */
	@Override
	public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
		// 断言 postProcessor 不为空
		Assert.notNull(postProcessor, "BeanFactoryPostProcessor must not be null");
		// 将 postProcessor 添加到 beanFactoryPostProcessors 列表中
		this.beanFactoryPostProcessors.add(postProcessor);
	}

	/**
	 * 返回将应用于内部 BeanFactory 的 BeanFactoryPostProcessor 列表。
	 */
	public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
		return this.beanFactoryPostProcessors;
	}

	/**
	 * 添加一个 ApplicationListener 到应用程序上下文中。
	 */
	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		// 断言 listener 不为空
		Assert.notNull(listener, "ApplicationListener must not be null");
		// 如果 applicationEventMulticaster 不为空，则将 listener 添加到其中
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.addApplicationListener(listener);
		}
		// 将 listener 添加到 applicationListeners 列表中
		this.applicationListeners.add(listener);
	}

	/**
	 * 从应用程序上下文中移除一个 ApplicationListener。
	 */
	@Override
	public void removeApplicationListener(ApplicationListener<?> listener) {
		// 断言 listener 不为空
		Assert.notNull(listener, "ApplicationListener must not be null");
		// 如果 applicationEventMulticaster 不为空，则从其中移除 listener
		if (this.applicationEventMulticaster != null) {
			this.applicationEventMulticaster.removeApplicationListener(listener);
		}
		// 从 applicationListeners 列表中移除 listener
		this.applicationListeners.remove(listener);
	}

	/**
	 * 返回静态指定的 ApplicationListeners 列表。
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
	 * 实例化和调用所有已注册的 BeanFactoryPostProcessor bean，
	 * 如果给定，则遵循显式顺序。
	 * <p>必须在单例实例化之前调用。
	 */
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 调用 PostProcessorRegistrationDelegate 的 invokeBeanFactoryPostProcessors 方法
		// 传入 beanFactory 和 beanFactoryPostProcessors 列表
		PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());

		// 检测 LoadTimeWeaver，并准备进行织入（例如通过 ConfigurationClassPostProcessor 注册的 @Bean 方法）
		if (!NativeDetector.inNativeImage() && beanFactory.getTempClassLoader() == null &&
				beanFactory.containsBean(LOAD_TIME_WEAVER_BEAN_NAME)) {
			// 添加一个 LoadTimeWeaverAwareProcessor，并设置临时类加载器
			beanFactory.addBeanPostProcessor(new LoadTimeWeaverAwareProcessor(beanFactory));
			beanFactory.setTempClassLoader(new ContextTypeMatchClassLoader(beanFactory.getBeanClassLoader()));
		}
	}

	/**
	 * 实例化和注册所有 BeanPostProcessor bean，
	 * 如果给定，则遵循显式顺序。
	 * <p>必须在应用程序 bean 的任何实例化之前调用。
	 */
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		// 调用 PostProcessorRegistrationDelegate 的 registerBeanPostProcessors 方法
		// 传入 beanFactory 和 this（当前应用程序上下文）
		PostProcessorRegistrationDelegate.registerBeanPostProcessors(beanFactory, this);
	}

	/**
	 * 初始化 MessageSource。
	 * <p>如果此上下文中未定义 MessageSource，则使用父级的 MessageSource。
	 *
	 * @see #MESSAGE_SOURCE_BEAN_NAME
	 */
	protected void initMessageSource() {
		// 获取 BeanFactory
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		// 获取 BeanFactory
		if (beanFactory.containsLocalBean(MESSAGE_SOURCE_BEAN_NAME)) {
			// 从 BeanFactory 中获取 MessageSource
			this.messageSource = beanFactory.getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);
			// 如果存在父上下文，并且 MessageSource 是 HierarchicalMessageSource 的实例，
			// 则设置父级 MessageSource
			if (this.parent != null && this.messageSource instanceof HierarchicalMessageSource hms &&
					hms.getParentMessageSource() == null) {
				// Only set parent context as parent MessageSource if no parent MessageSource
				// registered already.
				hms.setParentMessageSource(getInternalParentMessageSource());
			}
			// 输出trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("Using MessageSource [" + this.messageSource + "]");
			}
		} else {
			// 如果此上下文中未定义 MESSAGE_SOURCE_BEAN_NAME bean，则创建一个空的 DelegatingMessageSource
			// 以便接受 getMessage 调用
			DelegatingMessageSource dms = new DelegatingMessageSource();
			// 将父级 MessageSource 设置为内部父级 MessageSource
			dms.setParentMessageSource(getInternalParentMessageSource());
			this.messageSource = dms;
			// 在 BeanFactory 中注册 MESSAGE_SOURCE_BEAN_NAME bean
			beanFactory.registerSingleton(MESSAGE_SOURCE_BEAN_NAME, this.messageSource);
			// 输出trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + MESSAGE_SOURCE_BEAN_NAME + "' bean, using [" + this.messageSource + "]");
			}
		}
	}

	/**
	 * 初始化 ApplicationEventMulticaster。
	 * <p>如果上下文中未定义 ApplicationEventMulticaster，则使用 SimpleApplicationEventMulticaster。
	 *
	 * @see #APPLICATION_EVENT_MULTICASTER_BEAN_NAME
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster
	 */
	protected void initApplicationEventMulticaster() {
		// 获取 BeanFactory
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		// 如果此上下文包含 APPLICATION_EVENT_MULTICASTER_BEAN_NAME bean
		if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
			// 从 BeanFactory 中获取 ApplicationEventMulticaster
			this.applicationEventMulticaster =
					beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
			// 输出trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("Using ApplicationEventMulticaster [" + this.applicationEventMulticaster + "]");
			}
		} else {
			// 如果此上下文中未定义 APPLICATION_EVENT_MULTICASTER_BEAN_NAME bean，则创建一个 SimpleApplicationEventMulticaster
			this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
			// 在 BeanFactory 中注册 APPLICATION_EVENT_MULTICASTER_BEAN_NAME bean
			beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
			// 输出trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("No '" + APPLICATION_EVENT_MULTICASTER_BEAN_NAME + "' bean, using " +
						"[" + this.applicationEventMulticaster.getClass().getSimpleName() + "]");
			}
		}
	}

	/**
	 * 初始化 LifecycleProcessor。
	 * <p>如果上下文中未定义 LifecycleProcessor，则使用 DefaultLifecycleProcessor。
	 *
	 * @see #LIFECYCLE_PROCESSOR_BEAN_NAME
	 * @see org.springframework.context.support.DefaultLifecycleProcessor
	 * @since 3.0
	 */
	protected void initLifecycleProcessor() {
		// 获取 BeanFactory
		ConfigurableListableBeanFactory beanFactory = getBeanFactory();
		// 如果此上下文包含 LIFECYCLE_PROCESSOR_BEAN_NAME bean
		if (beanFactory.containsLocalBean(LIFECYCLE_PROCESSOR_BEAN_NAME)) {
			// 从 BeanFactory 中获取 LifecycleProcessor
			this.lifecycleProcessor = beanFactory.getBean(LIFECYCLE_PROCESSOR_BEAN_NAME, LifecycleProcessor.class);
			// 输出trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("Using LifecycleProcessor [" + this.lifecycleProcessor + "]");
			}
		} else {
			// 如果此上下文中未定义 LIFECYCLE_PROCESSOR_BEAN_NAME bean，则创建一个 DefaultLifecycleProcessor
			DefaultLifecycleProcessor defaultProcessor = new DefaultLifecycleProcessor();
			// 设置 BeanFactory
			defaultProcessor.setBeanFactory(beanFactory);
			this.lifecycleProcessor = defaultProcessor;
			// 在 BeanFactory 中注册 LIFECYCLE_PROCESSOR_BEAN_NAME bean
			beanFactory.registerSingleton(LIFECYCLE_PROCESSOR_BEAN_NAME, this.lifecycleProcessor);
			// 输出trace日志
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
	 * 完成此上下文的刷新，调用 LifecycleProcessor 的 onRefresh() 方法，
	 * 并发布 {@link org.springframework.context.event.ContextRefreshedEvent}。
	 */
	protected void finishRefresh() {
		// 重置 Spring 核心基础设施中的常见内省缓存。
		resetCommonCaches();

		// 清除上下文级别的资源缓存（例如来自扫描的 ASM 元数据）。
		clearResourceCaches();

		// 初始化此上下文的生命周期处理器。
		initLifecycleProcessor();

		// 首先将刷新传播到生命周期处理器。
		getLifecycleProcessor().onRefresh();

		// 发布最终事件。
		publishEvent(new ContextRefreshedEvent(this));
	}

	/**
	 * 取消此上下文的刷新尝试，在抛出异常后重置 {@code active} 标志。
	 *
	 * @param ex 导致取消的异常
	 */
	protected void cancelRefresh(Throwable ex) {
		this.active.set(false);

		// 重置 Spring 核心缓存。
		resetCommonCaches();
	}

	/**
	 * 重置 Spring 的常见反射元数据缓存，特别是 {@link ReflectionUtils}、
	 * {@link AnnotationUtils}、{@link ResolvableType}
	 * 和 {@link CachedIntrospectionResults} 缓存。
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
		// 调用父类的清除资源缓存方法
		super.clearResourceCaches();
		// 如果资源模式解析器是 PathMatchingResourcePatternResolver 类型的
		if (this.resourcePatternResolver instanceof PathMatchingResourcePatternResolver pmrpr) {
			//清除资源模式解析器的缓存
			pmrpr.clearCache();
		}
	}


	/**
	 * 使用 JVM 运行时注册一个名为 {@code SpringContextShutdownHook} 的关闭钩子线程，
	 * 在 JVM 关闭时关闭此上下文，除非在那时已经关闭。
	 * <p>委托 {@code doClose()} 进行实际的关闭过程。
	 *
	 * @see Runtime#addShutdownHook
	 * @see ConfigurableApplicationContext#SHUTDOWN_HOOK_THREAD_NAME
	 * @see #close()
	 * @see #doClose()
	 */
	@Override
	public void registerShutdownHook() {
		// 如果关闭钩子尚未注册
		if (this.shutdownHook == null) {
			// No shutdown hook registered yet.
			this.shutdownHook = new Thread(SHUTDOWN_HOOK_THREAD_NAME) {
				@Override
				public void run() {
					// 如果启动关闭线程卡住
					if (isStartupShutdownThreadStuck()) {
						active.set(false);
						return;
					}
					// 加锁以避免并发问题
					startupShutdownLock.lock();
					try {
						// 执行关闭逻辑
						doClose();
					} finally {
						// 解锁
						startupShutdownLock.unlock();
					}
				}
			};
			// 注册关闭钩子线程
			Runtime.getRuntime().addShutdownHook(this.shutdownHook);
		}
	}

	/**
	 * 判断当前是否存在处于活动状态的启动/关闭线程卡住，
	 * 例如在用户组件中调用 {@code System.exit}。
	 */
	private boolean isStartupShutdownThreadStuck() {
		// 获取活动的启动/关闭线程
		Thread activeThread = this.startupShutdownThread;
		// 如果活动线程不为空且处于等待状态
		if (activeThread != null && activeThread.getState() == Thread.State.WAITING) {
			// 永久等待：可能是 Thread.join 或类似的操作，或者是 System.exit
			// 中断活动线程
			activeThread.interrupt();
			try {
				// 等待一小段时间以确保中断生效
				Thread.sleep(1);
			} catch (InterruptedException ex) {
				// 恢复当前线程的中断状态
				Thread.currentThread().interrupt();
			}
			if (activeThread.getState() == Thread.State.WAITING) {
				// 如果活动线程仍然处于等待状态，则很可能是 System.exit 调用导致的
				return true;
			}
		}
		return false;
	}

	/**
	 * 关闭此应用程序上下文，销毁其 Bean 工厂中的所有 Bean。
	 * <p>委托 {@code doClose()} 执行实际的关闭过程。
	 * 还会移除注册的 JVM 关闭钩子，因为它不再需要。
	 *
	 * @see #doClose()
	 * @see #registerShutdownHook()
	 */
	@Override
	public void close() {
		// 如果启动/关闭线程卡住，则设置活动状态为 false 并返回
		if (isStartupShutdownThreadStuck()) {
			this.active.set(false);
			return;
		}

		// 加锁以避免并发问题
		this.startupShutdownLock.lock();
		try {
			// 将当前线程设置为启动/关闭线程
			this.startupShutdownThread = Thread.currentThread();
			// 执行关闭逻辑
			doClose();

			// 如果注册了 JVM 关闭钩子，则不再需要它：
			// 因为已经明确关闭了上下文。
			if (this.shutdownHook != null) {
				try {
					// 移除 JVM 关闭钩子
					Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
				} catch (IllegalStateException ex) {
					// 忽略 - VM 已经在关闭中
				}
			}
		} finally {
			// 清空启动/关闭线程，并释放锁
			this.startupShutdownThread = null;
			this.startupShutdownLock.unlock();
		}
	}

	/**
	 * 实际执行上下文关闭：发布 ContextClosedEvent 并销毁此应用程序上下文的 Bean 工厂中的单例。
	 * <p>由 {@code close()} 和 JVM 关闭钩子调用。
	 *
	 * @see org.springframework.context.event.ContextClosedEvent
	 * @see #destroyBeans()
	 * @see #close()
	 * @see #registerShutdownHook()
	 */
	protected void doClose() {
		// 检查是否需要进行实际关闭尝试...
		if (this.active.get() && this.closed.compareAndSet(false, true)) {
			if (logger.isDebugEnabled()) {
				logger.debug("Closing " + this);
			}

			try {
				// 发布关闭事件
				publishEvent(new ContextClosedEvent(this));
			} catch (Throwable ex) {
				logger.warn("Exception thrown from ApplicationListener handling ContextClosedEvent", ex);
			}

			// 停止所有 Lifecycle Bean，以避免在单个销毁期间出现延迟。
			if (this.lifecycleProcessor != null) {
				try {
					this.lifecycleProcessor.onClose();
				} catch (Throwable ex) {
					logger.warn("Exception thrown from LifecycleProcessor on context close", ex);
				}
			}

			// 销毁上下文的 BeanFactory 中的所有缓存单例。
			destroyBeans();

			// 关闭此上下文本身的状态。
			closeBeanFactory();

			// 如果需要，让子类进行一些最后的清理...
			onClose();

			// 重置公共的内置缓存以避免类引用泄漏。
			resetCommonCaches();

			// 将本地的应用程序监听器重置为刷新前的状态。
			if (this.earlyApplicationListeners != null) {
				this.applicationListeners.clear();
				this.applicationListeners.addAll(this.earlyApplicationListeners);
			}

			// 重置内部委托。
			this.applicationEventMulticaster = null;
			this.messageSource = null;
			this.lifecycleProcessor = null;

			// 切换为非活动状态。
			this.active.set(false);
		}
	}

	/**
	 * 模板方法，用于销毁此上下文管理的所有 Bean。
	 * 默认实现会销毁此上下文中的所有缓存单例，
	 * 调用 {@code DisposableBean.destroy()} 和/或指定的 "destroy-method"。
	 * <p>可以重写此方法，添加上下文特定的 Bean 销毁步骤，
	 * 位于标准单例销毁之前或之后，此时上下文的 BeanFactory 仍处于活动状态。
	 *
	 * @see #getBeanFactory()
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#destroySingletons()
	 */
	protected void destroyBeans() {
		//销毁单例池
		getBeanFactory().destroySingletons();
	}

	/**
	 * 模板方法，可以被重写以添加上下文特定的关闭工作。
	 * 默认实现为空。
	 * <p>在 {@link #doClose} 的关闭过程末尾调用，
	 * 在此上下文的 BeanFactory 被关闭之后。
	 * 如果需要在 BeanFactory 仍处于活动状态时执行自定义关闭逻辑，
	 * 则应重写 {@link #destroyBeans()} 方法。
	 */
	protected void onClose() {
		// 对于子类：默认情况下什么都不做
	}


	/**
	 * 判断当前标志是否活跃
	 *
	 * @return
	 */
	@Override
	public boolean isActive() {
		return this.active.get();
	}

	/**
	 * 断言此上下文的 BeanFactory 当前是否处于活动状态，
	 * 如果不是，则抛出 {@link IllegalStateException}。
	 * <p>由所有依赖于活动上下文的 {@link BeanFactory} 委托方法调用，
	 * 特别是所有 Bean 访问器方法。
	 * <p>默认实现检查此上下文的整体 {@link #isActive() 'active'} 状态。
	 * 可以针对更具体的检查进行重写，或者在此情况下 {@link #getBeanFactory()} 本身抛出异常时进行无操作。
	 */
	protected void assertBeanFactoryActive() {
		if (!this.active.get()) {
			if (this.closed.get()) {
				//关闭状态
				throw new IllegalStateException(getDisplayName() + " has been closed already");
			} else {
				throw new IllegalStateException(getDisplayName() + " has not been refreshed yet");
			}
		}
	}


	//---------------------------------------------------------------------
	//  BeanFactory 接口的实现
	//---------------------------------------------------------------------

	/**
	 * 根据名称获取一个bean实例
	 *
	 * @param name beanName
	 * @return
	 * @throws BeansException
	 */
	@Override
	public Object getBean(String name) throws BeansException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取bean实例
		return getBeanFactory().getBean(name);
	}

	/**
	 * 根据名称和要求的类型获取一个bean实例
	 *
	 * @param name         beanName
	 * @param requiredType type of Bean
	 * @param <T>
	 * @return
	 * @throws BeansException
	 */
	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取bean实例
		return getBeanFactory().getBean(name, requiredType);
	}

	/**
	 * 根据名称和参数获取一个bean实例
	 */
	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取bean实例
		return getBeanFactory().getBean(name, args);
	}

	/**
	 * 根据要求的类型获取一个bean实例
	 */
	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取bean实例
		return getBeanFactory().getBean(requiredType);
	}

	/**
	 * 根据要求的类型和参数获取一个bean实例
	 */
	@Override
	public <T> T getBean(Class<T> requiredType, Object... args) throws BeansException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取bean实例
		return getBeanFactory().getBean(requiredType, args);
	}

	/**
	 * 获取一个指定类型的ObjectProvider
	 */
	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取ObjectProvider
		return getBeanFactory().getBeanProvider(requiredType);
	}

	/**
	 * 获取一个指定类型的ObjectProvider
	 */
	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取ObjectProvider
		return getBeanFactory().getBeanProvider(requiredType);
	}

	/**
	 * 判断容器是否包含指定名称的bean
	 */
	@Override
	public boolean containsBean(String name) {
		// 委托给BeanFactory判断是否包含指定名称的bean
		return getBeanFactory().containsBean(name);
	}

	/**
	 * 判断指定名称的bean是否为单例
	 */
	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory判断指定名称的bean是否为单例
		return getBeanFactory().isSingleton(name);
	}

	/**
	 * 判断指定名称的bean是否为原型
	 */
	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory判断指定名称的bean是否为原型
		return getBeanFactory().isPrototype(name);
	}

	/**
	 * 判断指定名称的bean是否与指定类型匹配
	 */
	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory判断指定名称的bean是否与指定类型匹配
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	/**
	 * 判断指定名称的bean是否与指定类型匹配
	 */
	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory判断指定名称的bean是否与指定类型匹配
		return getBeanFactory().isTypeMatch(name, typeToMatch);
	}

	/**
	 * 获取指定名称的bean的类型
	 */
	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取指定名称的bean的类型
		return getBeanFactory().getType(name);
	}

	/**
	 * 获取指定名称的bean的类型
	 */
	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		// 断言BeanFactory处于活动状态
		assertBeanFactoryActive();
		// 委托给BeanFactory获取指定名称的bean的类型
		return getBeanFactory().getType(name, allowFactoryBeanInit);
	}

	/**
	 * 获取指定名称的bean的别名
	 */
	@Override
	public String[] getAliases(String name) {
		// 委托给BeanFactory获取指定名称的bean的别名
		return getBeanFactory().getAliases(name);
	}


	//---------------------------------------------------------------------
	// ListableBeanFactory 接口的实现
	//---------------------------------------------------------------------

	/**
	 * 判断BeanFactory中是否包含指定名称的Bean定义
	 */
	@Override
	public boolean containsBeanDefinition(String beanName) {
		return getBeanFactory().containsBeanDefinition(beanName);
	}

	/**
	 * 获取BeanFactory中定义的所有Bean的数量
	 */
	@Override
	public int getBeanDefinitionCount() {
		return getBeanFactory().getBeanDefinitionCount();
	}

	/**
	 * 获取BeanFactory中定义的所有Bean的名称
	 */
	@Override
	public String[] getBeanDefinitionNames() {
		return getBeanFactory().getBeanDefinitionNames();
	}

	/**
	 * 获取指定类型的ObjectProvider，用于延迟获取Bean实例
	 */
	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	/**
	 * 获取指定类型的ObjectProvider，用于延迟获取Bean实例
	 */
	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanProvider(requiredType, allowEagerInit);
	}

	/**
	 * 获取指定类型的所有Bean的名称
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	/**
	 * 获取指定类型的所有Bean的名称
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	/**
	 * 获取指定类型的所有Bean的名称
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type);
	}

	/**
	 * 获取指定类型的所有Bean的名称
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
	}

	/**
	 * 获取指定类型的所有Bean实例的Map
	 */
	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type);
	}

	/**
	 * 获取指定类型的所有Bean实例的Map
	 */
	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansOfType(type, includeNonSingletons, allowEagerInit);
	}

	/**
	 * 获取带有指定注解的所有Bean的名称
	 */
	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		assertBeanFactoryActive();
		return getBeanFactory().getBeanNamesForAnnotation(annotationType);
	}

	/**
	 * 获取带有指定注解的所有Bean的Map
	 */
	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType)
			throws BeansException {

		assertBeanFactoryActive();
		return getBeanFactory().getBeansWithAnnotation(annotationType);
	}

	/**
	 * 在指定Bean上查找指定类型的注解
	 */
	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType);
	}

	/**
	 * 在指定Bean上查找指定类型的注解
	 */
	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit);
	}

	/**
	 * 获取指定Bean上所有指定类型的注解的集合
	 */
	@Override
	public <A extends Annotation> Set<A> findAllAnnotationsOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		assertBeanFactoryActive();
		return getBeanFactory().findAllAnnotationsOnBean(beanName, annotationType, allowFactoryBeanInit);
	}


	//---------------------------------------------------------------------
	// 实现HierarchicalBeanFactory接口
	//---------------------------------------------------------------------

	/**
	 * 返回当前上下文的父级BeanFactory
	 */
	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return getParent();
	}


	/**
	 * 检查本地BeanFactory是否包含指定名称的Bean
	 */
	@Override
	public boolean containsLocalBean(String name) {
		return getBeanFactory().containsLocalBean(name);
	}

	/**
	 * 如果父上下文实现了ConfigurableApplicationContext，则返回其内部的Bean工厂；
	 * 否则，返回父上下文本身。
	 *
	 * @see org.springframework.context.ConfigurableApplicationContext#getBeanFactory
	 */
	@Nullable
	protected BeanFactory getInternalParentBeanFactory() {
		// 获取父上下文的内部Bean工厂
		return (getParent() instanceof ConfigurableApplicationContext cac ?
				cac.getBeanFactory() : getParent());
	}


	//---------------------------------------------------------------------
	// 实现 MessageSource 接口
	//---------------------------------------------------------------------

	/**
	 * 获取消息源中指定代码的消息
	 *
	 * @param code           要查找的消息代码，例如 'calculator.noRateSet'。
	 *                       鼓励 MessageSource 用户将消息名称基于合格的类
	 *                       或包名称，避免潜在的冲突并确保最大程度的清晰度。
	 * @param args           一个参数数组，将在其中填充参数
	 *                       消息（消息中的参数类似于“{0}”、“{1,date}”、“{2,time}”），
	 *                       或 {@code null} 如果没有
	 * @param defaultMessage 查找失败时返回的默认消息
	 * @param locale         进行查找的语言环境
	 * @return
	 */
	@Override
	public String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale) {
		return getMessageSource().getMessage(code, args, defaultMessage, locale);
	}

	/**
	 * 获取消息源中指定代码的消息
	 *
	 * @param code   要查找的消息代码，例如 '计算器.noRateSet'。
	 *               鼓励 MessageSource 用户将消息名称基于合格的类
	 *               或包名称，避免潜在的冲突并确保最大程度的清晰度。
	 * @param args   一个参数数组，将在其中填充参数
	 *               消息（消息中的参数类似于“{0}”、“{1,date}”、“{2,time}”），
	 *               或 {@code null} 如果没有
	 * @param locale 进行查找的区域设置
	 * @return
	 * @throws NoSuchMessageException
	 */
	@Override
	public String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(code, args, locale);
	}

	/**
	 * 获取消息源中指定解析的消息
	 *
	 * @param resolvable 存储解析消息所需属性的值对象
	 *                   （可能包括默认消息）
	 * @param locale     进行查找的语言环境
	 * @return
	 * @throws NoSuchMessageException
	 */
	@Override
	public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
		return getMessageSource().getMessage(resolvable, locale);
	}

	/**
	 * 返回上下文使用的内部MessageSource。
	 *
	 * @return 内部MessageSource（永不为null）
	 * @throws IllegalStateException 如果上下文尚未初始化
	 */
	private MessageSource getMessageSource() throws IllegalStateException {
		//没初始化
		if (this.messageSource == null) {
			throw new IllegalStateException("MessageSource not initialized - " +
					"call 'refresh' before accessing messages via the context: " + this);
		}
		// 返回上下文的内部MessageSource
		return this.messageSource;
	}

	/**
	 * 如果父上下文也是AbstractApplicationContext，则返回其内部的消息源；
	 * 否则，返回父上下文本身。
	 */
	@Nullable
	protected MessageSource getInternalParentMessageSource() {
		//获取父上下文的内部消息源
		return (getParent() instanceof AbstractApplicationContext abstractApplicationContext ?
				abstractApplicationContext.messageSource : getParent());
	}


	//---------------------------------------------------------------------
	// 实现 ResourcePatternResolver 接口
	//---------------------------------------------------------------------

	/**
	 * 解析给定的资源位置模式，返回匹配的资源数组
	 *
	 * @param locationPattern 解析的位置模式
	 * @return
	 * @throws IOException
	 */
	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		return this.resourcePatternResolver.getResources(locationPattern);
	}


	//---------------------------------------------------------------------
	// Lifecycle 接口的实现 生命周期接口
	//---------------------------------------------------------------------

	@Override
	public void start() {
		// 调用 LifecycleProcessor 的 start 方法，启动生命周期处理器
		getLifecycleProcessor().start();
		// 发布 ContextStartedEvent 事件，表示上下文已启动
		publishEvent(new ContextStartedEvent(this));
	}

	@Override
	public void stop() {
		// 调用 LifecycleProcessor 的 stop 方法，停止生命周期处理器
		getLifecycleProcessor().stop();
		// 发布 ContextStoppedEvent 事件，表示上下文已停止
		publishEvent(new ContextStoppedEvent(this));
	}

	@Override
	public boolean isRunning() {
		// 检查生命周期处理器是否不为 null，并且生命周期处理器是否正在运行
		return (this.lifecycleProcessor != null && this.lifecycleProcessor.isRunning());
	}


	//---------------------------------------------------------------------
	// 必须由子类实现的抽象方法
	//---------------------------------------------------------------------

	/**
	 * 子类必须实现此方法来执行实际的配置加载。
	 * 该方法在 {@link #refresh()} 之前由调用执行任何其他初始化工作。
	 * <p>子类将创建一个新的 bean 工厂并持有其引用，或者返回一个它持有的单个 BeanFactory 实例。
	 * 在后一种情况下，如果多次刷新上下文，则通常会抛出 IllegalStateException。
	 *
	 * @throws BeansException        如果 bean 工厂的初始化失败
	 * @throws IllegalStateException 如果已经初始化，并且不支持多次刷新尝试
	 */
	protected abstract void refreshBeanFactory() throws BeansException, IllegalStateException;

	/**
	 * 子类必须实现此方法以释放其内部的 bean 工厂。
	 * 该方法在所有其他关闭工作之后由 {@link #close()} 调用。
	 * <p>不应该抛出异常，而应该记录关闭失败。
	 */
	protected abstract void closeBeanFactory();

	/**
	 * 子类必须在此处返回它们的内部 bean 工厂。
	 * 它们应该有效地实现查找，以便可以重复调用而不会影响性能。
	 * <p>注意：子类应该在返回内部 bean 工厂之前检查上下文是否仍然处于活动状态。
	 * 一旦上下文已关闭，通常应该认为内部工厂不可用。
	 *
	 * @return 此应用程序上下文的内部 bean 工厂（永远不会为 {@code null}）
	 * @throws IllegalStateException 如果上下文尚未持有内部 bean 工厂
	 *                               （通常如果从未调用 {@link #refresh()}）或者如果上下文已关闭
	 * @see #refreshBeanFactory()
	 * @see #closeBeanFactory()
	 */
	@Override
	public abstract ConfigurableListableBeanFactory getBeanFactory() throws IllegalStateException;


	/**
	 * 返回关于此上下文的信息。
	 */
	@Override
	public String toString() {
		// 创建一个 StringBuilder 对象，并将 ApplicationContext 的显示名称添加到其中
		StringBuilder sb = new StringBuilder(getDisplayName());
		// 添加启动时间信息到 StringBuilder 中，使用 Date 对象格式化启动时间
		sb.append(", started on ").append(new Date(getStartupDate()));
		// 获取当前 上下文 的父级 ApplicationContext
		ApplicationContext parent = getParent();
		if (parent != null) {
			// 添加父级 ApplicationContext 的显示名称到 StringBuilder 中
			sb.append(", parent: ").append(parent.getDisplayName());
		}
		// 返回 StringBuilder 对象的字符串表示形式
		return sb.toString();
	}

}

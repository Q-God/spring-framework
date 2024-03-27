/*
 * Copyright 2002-2023 the original author or authors.
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

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.support.ClassHintUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.*;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ProtocolResolver;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 一个通用的 ApplicationContext 实现，它持有一个单独的内部
 * {@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
 * 实例，不假定特定的 Bean 定义格式。实现了
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * 接口，以允许将任何 Bean 定义读取器应用于其中。
 *
 * <p>典型的用法是通过
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * 接口注册各种 Bean 定义，然后调用 {@link #refresh()} 来使用应用程序上下文语义
 * 初始化这些 Bean（处理
 * {@link org.springframework.context.ApplicationContextAware}，自动检测
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor BeanFactoryPostProcessors}
 * 等）。
 *
 * <p>与其他 ApplicationContext 实现不同，它每次刷新都会为每个刷新创建一个新的
 * 内部 BeanFactory 实例，而此上下文的内部 BeanFactory 从一开始就是可用的，
 * 以便能够在其上注册 Bean 定义。只能调用一次 {@link #refresh()}。
 *
 * <p>此 ApplicationContext 实现适用于 Ahead of Time（AOT）处理，
 * 使用 {@link #refreshForAotProcessing} 作为常规 {@link #refresh()} 的替代方法。
 *
 * <p>使用示例：
 *
 * <pre class="code">
 * GenericApplicationContext ctx = new GenericApplicationContext();
 * XmlBeanDefinitionReader xmlReader = new XmlBeanDefinitionReader(ctx);
 * xmlReader.loadBeanDefinitions(new ClassPathResource("applicationContext.xml"));
 * PropertiesBeanDefinitionReader propReader = new PropertiesBeanDefinitionReader(ctx);
 * propReader.loadBeanDefinitions(new ClassPathResource("otherBeans.properties"));
 * ctx.refresh();
 *
 * MyBean myBean = (MyBean) ctx.getBean("myBean");
 * ...</pre>
 * <p>
 * 对于 XML Bean 定义的典型情况，您还可以使用
 * {@link ClassPathXmlApplicationContext} 或 {@link FileSystemXmlApplicationContext}，
 * 这些更易于设置 - 但不够灵活，因为您只能使用标准资源位置来定义 XML Bean，
 * 而不是混合任意 Bean 定义格式。对于自定义应用程序上下文实现，假设以可刷新的方式
 * 读取特定的 Bean 定义格式，请考虑从 {@link AbstractRefreshableApplicationContext}
 * 基类派生。
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Stephane Nicoll
 * @author Sam Brannen
 * @see #registerBeanDefinition
 * @see #refresh()
 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
 * @see org.springframework.beans.factory.support.PropertiesBeanDefinitionReader
 * @since 1.1.2
 */
public class GenericApplicationContext extends AbstractApplicationContext implements BeanDefinitionRegistry {

	//内部持有的 DefaultListableBeanFactory 实例
	private final DefaultListableBeanFactory beanFactory;

	// 可以设置的资源加载器
	@Nullable
	private ResourceLoader resourceLoader;

	// 是否使用自定义类加载器的标志
	private boolean customClassLoader = false;

	// 已刷新标志
	private final AtomicBoolean refreshed = new AtomicBoolean();


	/**
	 * 创建一个新的 GenericApplicationContext。
	 *
	 * @see #registerBeanDefinition
	 * @see #refresh
	 */
	public GenericApplicationContext() {
		this.beanFactory = new DefaultListableBeanFactory();
	}

	/**
	 * 创建一个新的 GenericApplicationContext，使用给定的 DefaultListableBeanFactory。
	 *
	 * @param beanFactory 要在此上下文中使用的 DefaultListableBeanFactory 实例
	 * @see #registerBeanDefinition
	 * @see #refresh
	 */
	public GenericApplicationContext(DefaultListableBeanFactory beanFactory) {
		Assert.notNull(beanFactory, "BeanFactory must not be null");
		this.beanFactory = beanFactory;
	}

	/**
	 * 使用给定的父上下文创建一个新的 GenericApplicationContext。
	 *
	 * @param parent 父应用程序上下文
	 * @see #registerBeanDefinition
	 * @see #refresh
	 */
	public GenericApplicationContext(@Nullable ApplicationContext parent) {
		// 调用默认构造函数
		this();
		// 设置父上下文
		setParent(parent);
	}

	/**
	 * 使用给定的 DefaultListableBeanFactory 创建一个新的 GenericApplicationContext。
	 *
	 * @param beanFactory 要用于此上下文的 DefaultListableBeanFactory 实例
	 * @param parent      父应用程序上下文
	 * @see #registerBeanDefinition
	 * @see #refresh
	 */
	public GenericApplicationContext(DefaultListableBeanFactory beanFactory, ApplicationContext parent) {
		// 调用带有 DefaultListableBeanFactory 参数的构造函数
		this(beanFactory);
		// 设置父上下文
		setParent(parent);
	}


	/**
	 * 创建一个新的 GenericApplicationContext，带有给定的父上下文。
	 *
	 * @param parent 父应用上下文
	 * @see #registerBeanDefinition
	 * @see #refresh
	 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#setParentBeanFactory
	 */
	@Override
	public void setParent(@Nullable ApplicationContext parent) {
		// 调用父类方法设置父上下文
		super.setParent(parent);
		// 设置内部 BeanFactory 的父 BeanFactory
		this.beanFactory.setParentBeanFactory(getInternalParentBeanFactory());
	}

	/**
	 * 设置应用程序启动时的策略。
	 *
	 * @param applicationStartup 应用程序启动时的策略
	 */
	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		// 调用父类方法设置应用程序启动策略
		super.setApplicationStartup(applicationStartup);
		// 设置 BeanFactory 的应用程序启动策略
		this.beanFactory.setApplicationStartup(applicationStartup);
	}

	/**
	 * 设置是否允许通过注册具有相同名称的不同定义来覆盖 Bean 定义，自动替换前者。
	 * 如果不允许，则会抛出异常。默认为 "true"。
	 *
	 * @param allowBeanDefinitionOverriding 是否允许覆盖 Bean 定义
	 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory#setAllowBeanDefinitionOverriding
	 * @since 3.0
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		// 设置是否允许覆盖 Bean 定义
		this.beanFactory.setAllowBeanDefinitionOverriding(allowBeanDefinitionOverriding);
	}

	/**
	 * 设置是否允许 Bean 之间存在循环引用，并自动尝试解决它们。
	 * <p>默认为 "true"。关闭此选项以在遇到循环引用时抛出异常，完全禁止它们。
	 *
	 * @param allowCircularReferences 是否允许循环引用
	 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory#setAllowCircularReferences
	 * @since 3.0
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		// 设置是否允许循环引用
		this.beanFactory.setAllowCircularReferences(allowCircularReferences);
	}

	/**
	 * 设置要用于此上下文的 ResourceLoader。如果设置了，则上下文将所有的 {@code getResource} 调用委托给给定的 ResourceLoader。
	 * 如果未设置，则将应用默认的资源加载方式。
	 * <p>指定自定义 ResourceLoader 的主要原因是以特定方式解析资源路径（不带 URL 前缀）。
	 * 默认行为是将这些路径解析为类路径位置。要将资源路径解析为文件系统位置，请在此处指定 FileSystemResourceLoader。
	 * <p>您还可以传入完整的 ResourcePatternResolver，该解析器将由上下文自动检测并用于 {@code getResources} 调用。否则，将应用默认的资源模式匹配。
	 *
	 * @param resourceLoader 要使用的 ResourceLoader
	 * @see #getResource
	 * @see org.springframework.core.io.DefaultResourceLoader
	 * @see org.springframework.core.io.FileSystemResourceLoader
	 * @see org.springframework.core.io.support.ResourcePatternResolver
	 * @see #getResources
	 */
	public void setResourceLoader(ResourceLoader resourceLoader) {
		// 设置要使用的 ResourceLoader
		this.resourceLoader = resourceLoader;
	}


	//---------------------------------------------------------------------
	// 如果有必要，重写 ResourceLoader / ResourcePatternResolver
	//---------------------------------------------------------------------

	/**
	 * 如果设置了自定义 ResourceLoader，则此实现将委托给该上下文的 {@code ResourceLoader}，否则将回退到默认的父类行为。
	 * <p>从 Spring Framework 5.3.22 开始，当设置了自定义 {@code ResourceLoader} 时，此方法还会尊重已注册的协议解析器。
	 *
	 * @param location 资源的位置
	 * @return 表示资源的 Resource 对象
	 * @see #setResourceLoader(ResourceLoader)
	 * @see #addProtocolResolver(ProtocolResolver)
	 */
	@Override
	public Resource getResource(String location) {
		// 如果设置了自定义 ResourceLoader
		if (this.resourceLoader != null) {
			// 遍历已注册的协议解析器
			for (ProtocolResolver protocolResolver : getProtocolResolvers()) {
				// 使用协议解析器解析资源
				Resource resource = protocolResolver.resolve(location, this);
				if (resource != null) {
					// 如果解析成功，则返回资源
					return resource;
				}
			}
			// 否则使用自定义 ResourceLoader 加载资源
			return this.resourceLoader.getResource(location);
		}
		// 否则使用默认父类行为加载资源
		return super.getResource(location);
	}

	/**
	 * 如果 ResourceLoader 实现了 ResourcePatternResolver 接口，则此实现将委托给该上下文的 ResourceLoader，否则将回退到默认的父类行为。
	 *
	 * @param locationPattern 资源的位置模式
	 * @return 表示资源的 Resource 对象数组
	 * @throws IOException 如果无法解析资源位置模式
	 * @see #setResourceLoader
	 */
	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		// 如果 ResourceLoader 实现了 ResourcePatternResolver 接口
		if (this.resourceLoader instanceof ResourcePatternResolver resourcePatternResolver) {
			// 使用 ResourcePatternResolver 解析资源
			return resourcePatternResolver.getResources(locationPattern);
		}
		// 否则使用默认父类行为加载资源
		return super.getResources(locationPattern);
	}

	/**
	 * 设置用于此上下文的 ClassLoader。如果设置了，则上下文将使用该 ClassLoader 进行加载资源和类。
	 * 如果未设置，则将应用默认的 ClassLoader。
	 *
	 * @param classLoader 要设置的 ClassLoader
	 * @see #getClassLoader
	 */
	@Override
	public void setClassLoader(@Nullable ClassLoader classLoader) {
		// 调用父类方法设置 ClassLoader
		super.setClassLoader(classLoader);
		// 设置自定义 ClassLoader 标志为 true
		this.customClassLoader = true;
	}

	/**
	 * 获取用于加载资源和类的 ClassLoader。
	 * 如果设置了自定义 ResourceLoader，并且未设置自定义 ClassLoader 标志，则返回该 ResourceLoader 的 ClassLoader。
	 * 否则，返回默认父类行为的 ClassLoader。
	 *
	 * @return 用于加载资源和类的 ClassLoader
	 * @see #setClassLoader
	 */
	@Override
	@Nullable
	public ClassLoader getClassLoader() {
		// 如果设置了自定义 ResourceLoader，并且未设置自定义 ClassLoader 标志
		if (this.resourceLoader != null && !this.customClassLoader) {
			// 返回 ResourceLoader 的 ClassLoader
			return this.resourceLoader.getClassLoader();
		}
		// 否则返回默认父类行为的 ClassLoader
		return super.getClassLoader();
	}


	//---------------------------------------------------------------------
	// AbstractApplicationContext 的模板方法的实现
	//---------------------------------------------------------------------

	/**
	 * 什么都不做：我们持有一个内部的 BeanFactory，并依赖于调用者通过我们的公共方法（或 BeanFactory 的方法）注册 bean。
	 *
	 * @see #registerBeanDefinition
	 */
	@Override
	protected final void refreshBeanFactory() throws IllegalStateException {
		// 检查是否已经刷新
		if (!this.refreshed.compareAndSet(false, true)) {
			// 抛出异常，因为 GenericApplicationContext 不支持多次刷新尝试
			throw new IllegalStateException(
					"GenericApplicationContext does not support multiple refresh attempts: just call 'refresh' once");
		}
		// 设置 BeanFactory 的序列化 ID
		this.beanFactory.setSerializationId(getId());
	}

	@Override
	protected void cancelRefresh(Throwable ex) {
		// 取消刷新，清除 BeanFactory 的序列化 ID
		this.beanFactory.setSerializationId(null);
		super.cancelRefresh(ex);
	}

	/**
	 * 不做太多事情：我们持有一个单一的内部 BeanFactory，它永远不会被释放。
	 */
	@Override
	protected final void closeBeanFactory() {
		// 关闭 BeanFactory，清除序列化 ID
		this.beanFactory.setSerializationId(null);
	}

	/**
	 * 返回此上下文持有的单个内部 BeanFactory（作为 ConfigurableListableBeanFactory）。
	 *
	 * @return 内部 BeanFactory（作为 ConfigurableListableBeanFactory）
	 */
	@Override
	public final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	/**
	 * 返回此上下文的基础 BeanFactory，可用于注册 Bean 定义。
	 * <p><b>注意：</b> 您需要调用 {@link #refresh()} 来初始化 BeanFactory 及其包含的 bean，以便具有应用程序上下文的语义（自动检测 BeanFactoryPostProcessors 等）。
	 *
	 * @return 内部 BeanFactory（作为 DefaultListableBeanFactory）
	 */
	public final DefaultListableBeanFactory getDefaultListableBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
		// 断言 BeanFactory 是否是活的
		assertBeanFactoryActive();
		return this.beanFactory;
	}


	//---------------------------------------------------------------------
	// BeanDefinitionRegistry 的实现
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {
		// 注册 Bean 定义到 BeanFactory 中
		this.beanFactory.registerBeanDefinition(beanName, beanDefinition);
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		// 从 BeanFactory 中移除 Bean 定义
		this.beanFactory.removeBeanDefinition(beanName);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		// 获取 Bean 定义
		return this.beanFactory.getBeanDefinition(beanName);
	}

	@Override
	public boolean isBeanDefinitionOverridable(String beanName) {
		// 检查是否允许覆盖 Bean 定义
		return this.beanFactory.isBeanDefinitionOverridable(beanName);
	}

	@Override
	public boolean isBeanNameInUse(String beanName) {
		// 检查 Bean 名称是否已在使用
		return this.beanFactory.isBeanNameInUse(beanName);
	}

	@Override
	public void registerAlias(String beanName, String alias) {
		// 注册别名
		this.beanFactory.registerAlias(beanName, alias);
	}

	@Override
	public void removeAlias(String alias) {
		// 移除别名
		this.beanFactory.removeAlias(alias);
	}

	@Override
	public boolean isAlias(String beanName) {
		// 检查是否是别名
		return this.beanFactory.isAlias(beanName);
	}


	//---------------------------------------------------------------------
	// AOT 处理
	//---------------------------------------------------------------------

	/**
	 * 加载或刷新配置的持久化表示，直到底层的 Bean 工厂准备好创建 Bean 实例为止。
	 * <p>这个 {@link #refresh()} 的变体被 Ahead of Time (AOT) 处理使用，用于在构建时优化应用程序上下文。
	 * <p>在这种模式下，只有 {@link BeanDefinitionRegistryPostProcessor} 和
	 * {@link MergedBeanDefinitionPostProcessor} 被调用。
	 *
	 * @param runtimeHints 运行时提示
	 * @throws BeansException        如果无法初始化 Bean 工厂
	 * @throws IllegalStateException 如果已经初始化并且不支持多次刷新尝试
	 * @since 6.0
	 */
	public void refreshForAotProcessing(RuntimeHints runtimeHints) {
		if (logger.isDebugEnabled()) {
			logger.debug("Preparing bean factory for AOT processing");
		}
		// 准备刷新
		prepareRefresh();
		// 获取最新的 Bean 工厂
		obtainFreshBeanFactory();
		// 准备 Bean 工厂
		prepareBeanFactory(this.beanFactory);
		// 后置处理器 Bean 工厂
		postProcessBeanFactory(this.beanFactory);
		// 调用 Bean 工厂后置处理器
		invokeBeanFactoryPostProcessors(this.beanFactory);
		// 冻结配置
		this.beanFactory.freezeConfiguration();
		// 调用合并的 Bean 定义后处理器
		PostProcessorRegistrationDelegate.invokeMergedBeanDefinitionPostProcessors(this.beanFactory);
		// 预定 bean 类型
		preDetermineBeanTypes(runtimeHints);
	}

	/**
	 * 预先确定 Bean 的类型以触发早期代理类的创建。
	 * 遍历 BeanFactory 中所有注册的 Bean 定义，为每个 Bean 定义预先确定其类型。
	 * 如果 Bean 的类型不为空，则尝试通过注册代理来优化类型。
	 * 对于每个注册的 SmartInstantiationAwareBeanPostProcessor，尝试确定 Bean 的新类型，
	 * 如果新类型与原始类型不同，则尝试通过注册代理来优化新类型。
	 *
	 * @param runtimeHints 运行时提示，用于优化 AOT 处理
	 * @see org.springframework.beans.factory.BeanFactory#getType
	 * @see SmartInstantiationAwareBeanPostProcessor#determineBeanType
	 * @see ClassHintUtils#registerProxyIfNecessary
	 */
	private void preDetermineBeanTypes(RuntimeHints runtimeHints) {
		// 加载所有 SmartInstantiationAwareBeanPostProcessor
		List<SmartInstantiationAwareBeanPostProcessor> bpps =
				PostProcessorRegistrationDelegate.loadBeanPostProcessors(
						this.beanFactory, SmartInstantiationAwareBeanPostProcessor.class);
		// 遍历所有 Bean 定义
		for (String beanName : this.beanFactory.getBeanDefinitionNames()) {
			// 获取 Bean 的类型
			Class<?> beanType = this.beanFactory.getType(beanName);
			// 如果 Bean 的类型不为空，尝试通过注册代理来优化类型
			if (beanType != null) {
				ClassHintUtils.registerProxyIfNecessary(beanType, runtimeHints);
				// 对于每个 SmartInstantiationAwareBeanPostProcessor
				for (SmartInstantiationAwareBeanPostProcessor bpp : bpps) {
					// 尝试确定 Bean 的新类型
					Class<?> newBeanType = bpp.determineBeanType(beanType, beanName);
					if (newBeanType != beanType) {
						// 如果新类型与原始类型不同，则尝试通过注册代理来优化新类型
						ClassHintUtils.registerProxyIfNecessary(newBeanType, runtimeHints);
						beanType = newBeanType;
					}
				}
			}
		}
	}


	//---------------------------------------------------------------------
	// 用于注册单个bean的便捷方法
	//---------------------------------------------------------------------

	/**
	 * 从给定的bean类注册一个bean，可选择提供显式构造函数参数以用于自动装配过程。
	 *
	 * @param beanClass       bean的类
	 * @param constructorArgs 用于Spring构造函数解析算法的自定义参数值，可解析所有参数或特定参数
	 * @since 5.2 (在AnnotationConfigApplicationContext子类中自5.0起)
	 */
	public <T> void registerBean(Class<T> beanClass, Object... constructorArgs) {
		registerBean(null, beanClass, constructorArgs);
	}

	/**
	 * 从给定的bean类注册一个bean，可选择提供显式构造函数参数以用于自动装配过程。
	 *
	 * @param beanName        bean的名称（可能为null）
	 * @param beanClass       bean的类
	 * @param constructorArgs 用于Spring构造函数解析算法的自定义参数值，可解析所有参数或特定参数
	 * @since 5.2 (在AnnotationConfigApplicationContext子类中自5.0起)
	 */
	public <T> void registerBean(@Nullable String beanName, Class<T> beanClass, Object... constructorArgs) {
		registerBean(beanName, beanClass, (Supplier<T>) null,
				bd -> {
					// 遍历提供的构造函数参数
					for (Object arg : constructorArgs) {
						// 将每个参数添加到 BeanDefinition 的构造函数参数值中
						bd.getConstructorArgumentValues().addGenericArgumentValue(arg);
					}
				});
	}

	/**
	 * 从给定的 bean 类注册一个 bean，可选择自定义其 bean 定义元数据（通常声明为 lambda 表达式）。
	 *
	 * @param beanClass   bean 的类（解析一个公共构造函数以进行自动装配，可能只是默认构造函数）
	 * @param customizers 用于自定义工厂的一个或多个回调的 {@link BeanDefinition}，例如设置延迟初始化或主要标志
	 * @see #registerBean(String, Class, Supplier, BeanDefinitionCustomizer...)
	 * @since 5.0
	 */
	public final <T> void registerBean(Class<T> beanClass, BeanDefinitionCustomizer... customizers) {
		registerBean(null, beanClass, null, customizers);
	}

	/**
	 * 从给定的 bean 类注册一个 bean，可选择自定义其 bean 定义元数据（通常声明为 lambda 表达式）。
	 *
	 * @param beanName    bean 的名称（可能为 {@code null}）
	 * @param beanClass   bean 的类（解析一个公共构造函数以进行自动装配，可能只是默认构造函数）
	 * @param customizers 用于自定义工厂的一个或多个回调的 {@link BeanDefinition}，例如设置延迟初始化或主要标志
	 * @see #registerBean(String, Class, Supplier, BeanDefinitionCustomizer...)
	 * @since 5.0
	 */
	public final <T> void registerBean(
			@Nullable String beanName, Class<T> beanClass, BeanDefinitionCustomizer... customizers) {

		registerBean(beanName, beanClass, null, customizers);
	}

	/**
	 * 从给定的 bean 类注册一个 bean，使用给定的供应商来获取一个新实例（通常声明为 lambda 表达式或方法引用），
	 * 可选择自定义其 bean 定义元数据（通常声明为 lambda 表达式）。
	 *
	 * @param beanClass   bean 的类
	 * @param supplier    创建 bean 实例的回调
	 * @param customizers 用于自定义工厂的一个或多个回调的 {@link BeanDefinition}，例如设置延迟初始化或主要标志
	 * @see #registerBean(String, Class, Supplier, BeanDefinitionCustomizer...)
	 * @since 5.0
	 */
	public final <T> void registerBean(
			Class<T> beanClass, Supplier<T> supplier, BeanDefinitionCustomizer... customizers) {

		registerBean(null, beanClass, supplier, customizers);
	}

	/**
	 * 注册一个来自给定 bean 类的 bean，使用给定的供应商获取一个新实例（通常声明为 lambda 表达式或方法引用），
	 * 可选地自定义其 bean 定义元数据（通常也声明为 lambda 表达式）。
	 * <p>此方法可以被重写以调整所有 {@code registerBean} 方法的注册机制（因为它们都委托给此方法）。
	 *
	 * @param beanName    bean 的名称（可以为 {@code null}）
	 * @param beanClass   bean 的类
	 * @param supplier    用于创建 bean 实例的回调函数（如果为 {@code null}，则解析一个公共构造函数进行自动装配）
	 * @param customizers 用于自定义工厂的一个或多个回调，例如设置懒加载或主要标志的回调
	 * @since 5.0
	 */
	public <T> void registerBean(@Nullable String beanName, Class<T> beanClass,
								 @Nullable Supplier<T> supplier, BeanDefinitionCustomizer... customizers) {

		// 创建一个 ClassDerivedBeanDefinition 对象，其中包含了给定 beanClass 的信息
		ClassDerivedBeanDefinition beanDefinition = new ClassDerivedBeanDefinition(beanClass);
		// 如果提供了 supplier，则设置 bean 的实例供应商
		if (supplier != null) {
			beanDefinition.setInstanceSupplier(supplier);
		}
		// 应用传入的 BeanDefinitionCustomizer 对象，用于自定义 bean 的定义
		for (BeanDefinitionCustomizer customizer : customizers) {
			customizer.customize(beanDefinition);
		}
		// 确定要使用的 bean 名称，如果未提供，则使用 beanClass 的名称
		String nameToUse = (beanName != null ? beanName : beanClass.getName());
		// 注册 beanDefinition，将 beanClass 与 beanName 关联起来
		registerBeanDefinition(nameToUse, beanDefinition);
	}


	/**
	 * {@code #registerBean} 方法的基于灵活自动装配的具有公共构造函数的注册所使用的
	 * {@link RootBeanDefinition} 的子类。
	 */
	@SuppressWarnings("serial")
	private static class ClassDerivedBeanDefinition extends RootBeanDefinition {

		/**
		 * 使用给定的 bean 类创建一个新的 ClassDerivedBeanDefinition 实例。
		 *
		 * @param beanClass bean 的类
		 */
		public ClassDerivedBeanDefinition(Class<?> beanClass) {
			super(beanClass);
		}

		/**
		 * 使用给定的原始 ClassDerivedBeanDefinition 实例创建一个新的 ClassDerivedBeanDefinition 实例。
		 *
		 * @param original 原始的 ClassDerivedBeanDefinition 实例
		 */
		public ClassDerivedBeanDefinition(ClassDerivedBeanDefinition original) {
			super(original);
		}

		/**
		 * 获取首选的构造函数，支持灵活自动装配。
		 *
		 * @return 首选的构造函数数组，如果没有找到，则返回 {@code null}
		 */
		@Override
		@Nullable
		public Constructor<?>[] getPreferredConstructors() {
			// 获取从父类继承的首选构造函数
			Constructor<?>[] fromAttribute = super.getPreferredConstructors();
			if (fromAttribute != null) {
				return fromAttribute;
			}
			// 获取 bean 类
			Class<?> clazz = getBeanClass();
			// 查找主要构造函数（由 BeanUtils.findPrimaryConstructor 方法确定）
			Constructor<?> primaryCtor = BeanUtils.findPrimaryConstructor(clazz);
			if (primaryCtor != null) {
				return new Constructor<?>[]{primaryCtor};
			}
			// 获取所有公共构造函数
			Constructor<?>[] publicCtors = clazz.getConstructors();
			if (publicCtors.length > 0) {
				return publicCtors;
			}
			// 如果未找到任何构造函数，则返回 null
			return null;
		}


		/**
		 * 克隆此 bean 定义。
		 *
		 * @return 新的 ClassDerivedBeanDefinition 实例，与当前实例具有相同的属性
		 */
		@Override
		public RootBeanDefinition cloneBeanDefinition() {
			return new ClassDerivedBeanDefinition(this);
		}
	}

}

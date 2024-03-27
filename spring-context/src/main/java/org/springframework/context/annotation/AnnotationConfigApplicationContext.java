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

package org.springframework.context.annotation;

import org.springframework.beans.factory.config.BeanDefinitionCustomizer;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 独立应用程序上下文，接受<em>组件类</em>作为输入 -
 * 特别是{@link Configuration @Configuration}注释的类，但也是普通的
 * {@link org.springframework.stereotype.Component @Component} 类型和 JSR-330 兼容
 * 使用 {@code jakarta.inject} 注释的类。
 *
 * <p>允许使用 {@link #register(Class...)} 逐个注册类
 * 以及使用 {@link #scan(String...)} 进行类路径扫描。
 *
 * <p>如果有多个{@code @Configuration}类，则使用{@link Bean @Bean}方法
 * 在后面的类中定义的内容将覆盖在前面的类中定义的内容。 这个可以
 * 通过额外的方法故意覆盖某些 bean 定义
 * {@code @Configuration} 类。
 *
 * <p>请参阅 {@link Configuration @Configuration} 的 javadoc 以获取使用示例。
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @see #register
 * @see #scan
 * @see AnnotatedBeanDefinitionReader
 * @see ClassPathBeanDefinitionScanner
 * @see org.springframework.context.support.GenericXmlApplicationContext
 * @since 3.0
 */
public class AnnotationConfigApplicationContext extends GenericApplicationContext implements AnnotationConfigRegistry {

	// 用于读取和解析带有注解的 bean 定义，并将其注册到 Spring 应用程序上下文中。
	private final AnnotatedBeanDefinitionReader reader;

	//用于扫描指定的包路径下的类，并根据这些类的信息创建相应的 bean 定义，并将这些 bean 定义注册到 Spring 应用程序上下文中。
	private final ClassPathBeanDefinitionScanner scanner;


	/**
	 * 创建一个需要填充的新AnnotationConfigApplicationContext
	 * 通过{@link #register}调用然后手动{@linkplain #refresh 刷新}。
	 */
	public AnnotationConfigApplicationContext() {
		// 开始跟踪创建 AnnotatedBeanDefinitionReader 的步骤
		StartupStep createAnnotatedBeanDefReader = getApplicationStartup().start("spring.context.annotated-bean-reader.create");
		//实例化 AnnotatedBeanDefinitionReader
		this.reader = new AnnotatedBeanDefinitionReader(this);
		// 结束跟踪步骤
		createAnnotatedBeanDefReader.end();
		// 实例化ClassPathBeanDefinitionScanner
		this.scanner = new ClassPathBeanDefinitionScanner(this);
	}

	/**
	 * 使用给定的 DefaultListableBeanFactory 创建一个新的 AnnotationConfigApplicationContext。
	 *
	 * @param beanFactory DefaultListableBeanFactory
	 *                    用于此上下文的实例
	 */
	public AnnotationConfigApplicationContext(DefaultListableBeanFactory beanFactory) {
		//调用父类构造器
		super(beanFactory);
		//实例化 AnnotatedBeanDefinitionReader
		this.reader = new AnnotatedBeanDefinitionReader(this);
		//实例化ClassPathBeanDefinitionScanner
		this.scanner = new ClassPathBeanDefinitionScanner(this);
	}

	/**
	 * 创建一个新的AnnotationConfigApplicationContext，派生bean定义
	 * 从给定的组件类中自动刷新上下文。
	 *
	 * @param componentClasses one or more component classes &mdash; for example,
	 *                         {@link Configuration @Configuration} classes
	 */
	public AnnotationConfigApplicationContext(Class<?>... componentClasses) {

		// 调用默认构造函数
		this();
		// 注册给定的组件类
		register(componentClasses);
		// 刷新上下文
		refresh();
	}

	/**
	 * 创建一个新的 AnnotationConfigApplicationContext 实例，扫描给定的包以查找组件类，为这些组件注册 bean 定义，并自动刷新上下文。
	 *
	 * @param basePackages 要扫描以查找组件类的包
	 */
	public AnnotationConfigApplicationContext(String... basePackages) {
		// 调用默认构造函数
		this();
		// 扫描给定的包以查找组件类
		scan(basePackages);
		// 刷新上下文
		refresh();
	}


	/**
	 * 将给定的自定义 {@code Environment} 传播到底层的
	 * {@link AnnotatedBeanDefinitionReader} 和 {@link ClassPathBeanDefinitionScanner}。
	 */
	@Override
	public void setEnvironment(ConfigurableEnvironment environment) {
		// 调用父类的设置环境方法
		super.setEnvironment(environment);
		// 将环境设置到注解的 Bean 定义阅读器和类路径 Bean 定义扫描器中
		this.reader.setEnvironment(environment);
		this.scanner.setEnvironment(environment);
	}

	/**
	 * 为 {@link AnnotatedBeanDefinitionReader} 和/或 {@link ClassPathBeanDefinitionScanner} 提供自定义的 {@link BeanNameGenerator}。
	 * <p>默认值为 {@link AnnotationBeanNameGenerator}。
	 * <p>调用此方法必须在调用 {@link #register(Class...)} 和/或 {@link #scan(String...)} 之前发生。
	 *
	 * @see AnnotatedBeanDefinitionReader#setBeanNameGenerator
	 * @see ClassPathBeanDefinitionScanner#setBeanNameGenerator
	 * @see AnnotationBeanNameGenerator
	 * @see FullyQualifiedAnnotationBeanNameGenerator
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		// 将 Bean 名称生成器设置到注解的 Bean 定义阅读器和类路径 Bean 定义扫描器中
		this.reader.setBeanNameGenerator(beanNameGenerator);
		this.scanner.setBeanNameGenerator(beanNameGenerator);
		// 将 Bean 名称生成器注册为单例 bean
		getBeanFactory().registerSingleton(
				AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR, beanNameGenerator);
	}

	/**
	 * 设置要用于注册的组件类的 {@link ScopeMetadataResolver}。
	 * <p>默认为 {@link AnnotationScopeMetadataResolver}。
	 * <p>调用此方法必须在调用 {@link #register(Class...)} 和/或 {@link #scan(String...)} 之前发生。
	 */
	public void setScopeMetadataResolver(ScopeMetadataResolver scopeMetadataResolver) {
		// 设置Scope元数据解析器到注解的 Bean 定义阅读器和类路径 Bean 定义扫描器中
		this.reader.setScopeMetadataResolver(scopeMetadataResolver);
		this.scanner.setScopeMetadataResolver(scopeMetadataResolver);
	}


	//---------------------------------------------------------------------
	// Implementation of AnnotationConfigRegistry
	//---------------------------------------------------------------------

	/**
	 * 注册一个或多个要处理的组件类。
	 * <p>请注意，必须调用 {@link #refresh()} 才能使上下文完全处理新类。
	 *
	 * @param componentClasses 一个或多个组件类，例如 {@link Configuration @Configuration} 类
	 * @see #scan(String...)
	 * @see #refresh()
	 */
	@Override
	public void register(Class<?>... componentClasses) {
		// 检查组件类数组是否为空
		Assert.notEmpty(componentClasses, "At least one component class must be specified");
		// 启动性能监视器
		StartupStep registerComponentClass = getApplicationStartup().start("spring.context.component-classes.register")
				.tag("classes", () -> Arrays.toString(componentClasses));
		// 向注解的 Bean 定义阅读器注册组件类
		this.reader.register(componentClasses);
		// 结束性能监视器
		registerComponentClass.end();
	}

	/**
	 * 在指定的基础包中执行扫描。
	 * <p>请注意，必须调用 {@link #refresh()} 才能使上下文完全处理新类。
	 *
	 * @param basePackages 要扫描的基础包
	 * @see #register(Class...)
	 * @see #refresh()
	 */
	@Override
	public void scan(String... basePackages) {
		// 检查基础包数组是否为空
		Assert.notEmpty(basePackages, "At least one base package must be specified");
		// 启动性能监视器
		StartupStep scanPackages = getApplicationStartup().start("spring.context.base-packages.scan")
				.tag("packages", () -> Arrays.toString(basePackages));
		// 扫描指定的基础包
		this.scanner.scan(basePackages);
		// 结束性能监视器
		scanPackages.end();
	}


	//---------------------------------------------------------------------
	// 将超类的 registerBean 调用适配到 AnnotatedBeanDefinitionReader。
	//---------------------------------------------------------------------

	@Override
	public <T> void registerBean(@Nullable String beanName, Class<T> beanClass,
								 @Nullable Supplier<T> supplier, BeanDefinitionCustomizer... customizers) {
		// 调用注解的 Bean 定义阅读器的注册 bean 方法
		this.reader.registerBean(beanClass, beanName, supplier, customizers);
	}

}

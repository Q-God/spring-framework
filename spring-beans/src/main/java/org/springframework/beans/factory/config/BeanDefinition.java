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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;

/**
 * A BeanDefinition describes a bean instance, which has property values,
 * constructor argument values, and further information supplied by
 * concrete implementations.
 *
 * <p>This is just a minimal interface: The main intention is to allow a
 * {@link BeanFactoryPostProcessor} to introspect and modify property values
 * and other bean metadata.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @see ConfigurableListableBeanFactory#getBeanDefinition
 * @see org.springframework.beans.factory.support.RootBeanDefinition
 * @see org.springframework.beans.factory.support.ChildBeanDefinition
 * @since 19.03.2004
 */
public interface BeanDefinition extends AttributeAccessor, BeanMetadataElement {

	/**
	 * 单例Bean标识符
	 * Scope identifier for the standard singleton scope: {@value}.
	 * <p>Note that extended bean factories might support further scopes.
	 *
	 * @see #setScope
	 * @see ConfigurableBeanFactory#SCOPE_SINGLETON
	 */
	String SCOPE_SINGLETON = ConfigurableBeanFactory.SCOPE_SINGLETON;

	/**
	 * 原型Bean标识符
	 * Scope identifier for the standard prototype scope: {@value}.
	 * <p>Note that extended bean factories might support further scopes.
	 *
	 * @see #setScope
	 * @see ConfigurableBeanFactory#SCOPE_PROTOTYPE
	 */
	String SCOPE_PROTOTYPE = ConfigurableBeanFactory.SCOPE_PROTOTYPE;


	/**
	 * 表示 {@code BeanDefinition} 是应用程序的主要部分的角色提示
	 * <p>
	 * Role hint indicating that a {@code BeanDefinition} is a major part
	 * of the application. Typically corresponds to a user-defined bean.
	 */
	int ROLE_APPLICATION = 0;

	/**
	 * 表示 {@code BeanDefinition} 是某个更大配置的支持部分的角色提示
	 * Role hint indicating that a {@code BeanDefinition} is a supporting
	 * part of some larger configuration, typically an outer
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 * {@code SUPPORT} beans are considered important enough to be aware
	 * of when looking more closely at a particular
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition},
	 * but not when looking at the overall configuration of an application.
	 */
	int ROLE_SUPPORT = 1;

	/**
	 * 表示 {@code BeanDefinition} 提供完全是后台角色，对最终用户无关的角色提示
	 * Role hint indicating that a {@code BeanDefinition} is providing an
	 * entirely background role and has no relevance to the end-user. This hint is
	 * used when registering beans that are completely part of the internal workings
	 * of a {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 */
	int ROLE_INFRASTRUCTURE = 2;


	// Modifiable attributes

	/**
	 * 设置此 bean 定义的父定义的名称（如果有）。
	 * Set the name of the parent definition of this bean definition, if any.
	 */
	void setParentName(@Nullable String parentName);

	/**
	 * 返回此 bean 定义的父定义的名称（如果有）。
	 * Return the name of the parent definition of this bean definition, if any.
	 */
	@Nullable
	String getParentName();

	/**
	 * 指定此 bean 定义的 bean 类名称。
	 * 指定此 bean 定义的 bean 类名。
	 * <p>在 bean 工厂后处理期间，可以修改类名，通常将原始类名替换为解析后的变体。
	 *
	 * @see #setParentName
	 * @see #setFactoryBeanName
	 * @see #setFactoryMethodName
	 */
	void setBeanClassName(@Nullable String beanClassName);

	/**
	 * 返回此 bean 定义的当前 bean 类名称。
	 * <p>
	 * 返回此 bean 定义的当前 bean 类名。
	 * <p>注意，这不一定是运行时使用的实际类名，在子定义覆盖/继承父类名的情况下。
	 * 此外，这可能只是调用工厂方法的类，或者在调用方法的情况下可能为空。
	 * 因此，在运行时不要将其视为确定的 bean 类型，而只将其用于单个 bean 定义级别的解析目的。
	 *
	 * @see #getParentName()
	 * @see #getFactoryBeanName()
	 * @see #getFactoryMethodName()
	 */
	@Nullable
	String getBeanClassName();

	/**
	 * 覆盖此 bean 的目标SCOPE，指定新的SCOPE名称。
	 *
	 * @see #SCOPE_SINGLETON
	 * @see #SCOPE_PROTOTYPE
	 */
	void setScope(@Nullable String scope);

	/**
	 * 返回当前bean 的目标SCOPE，
	 * or {@code null} if not known yet.
	 */
	@Nullable
	String getScope();

	/**
	 * 设置当前 bean 是否懒加载初始化
	 * <p>如果{@code false}，bean将在启动时由bean实例化
	 * 执行单例急切初始化的工厂。
	 */
	void setLazyInit(boolean lazyInit);

	/**
	 * 返回当前 bean 是否懒加载初始化
	 * Return whether this bean should be lazily initialized, i.e. not
	 * eagerly instantiated on startup. Only applicable to a singleton bean.
	 */
	boolean isLazyInit();

	/**
	 * 设置当前bean初始化所依赖的bean的名称。
	 * bean 工厂将保证这些 bean 首先被初始化。
	 * <p>请注意，依赖关系通常通过 bean 属性或
	 * 构造函数参数。这个属性应该是其他类型所必需的
	 * 依赖项，如启动时的静态数据（*呃*）或数据库准备。
	 */
	void setDependsOn(@Nullable String... dependsOn);

	/**
	 * 获取当前Bean的依赖Bean
	 */
	@Nullable
	String[] getDependsOn();

	/***
	 * 设置此 bean 是否是自动连接到其他 bean 的候选者。
	 * <p>请注意，此标志旨在仅影响基于类型的自动装配。
	 * 它不会影响按名称显式引用，甚至会得到解决
	 * 如果指定的 bean 未标记为自动装配候选者。作为结果，
	 * 如果名称匹配，按名称自动装配仍然会注入一个 bean。
	 */
	void setAutowireCandidate(boolean autowireCandidate);

	/**
	 * 返回此 bean 是否是自动装配到其他 bean 中的候选者。
	 */
	boolean isAutowireCandidate();

	/**
	 * 设置此 bean 是否是优先自动装配候选者。 @Primary注解
	 * <p>如果多个 bean 中的一个 bean 的值为 {@code true}
	 * 匹配候选人，将作为决胜局。
	 *
	 * @see #setFallback
	 */
	void setPrimary(boolean primary);

	/**
	 * 返回此 bean 是否是有优先自动装配候选者。
	 */
	boolean isPrimary();

	/**
	 * Set whether this bean is a fallback autowire candidate.
	 * <p>If this value is {@code true} for all beans but one among multiple
	 * matching candidates, the remaining bean will be selected.
	 *
	 * @see #setPrimary
	 * @since 6.2
	 */
	void setFallback(boolean fallback);

	/**
	 * Return whether this bean is a fallback autowire candidate.
	 *
	 * @since 6.2
	 */
	boolean isFallback();

	/**
	 * Specify the factory bean to use, if any.
	 * This is the name of the bean to call the specified factory method on.
	 * <p>A factory bean name is only necessary for instance-based factory methods.
	 * For static factory methods, the method will be derived from the bean class.
	 *
	 * @see #setFactoryMethodName
	 * @see #setBeanClassName
	 */
	void setFactoryBeanName(@Nullable String factoryBeanName);

	/**
	 * Return the factory bean name, if any.
	 * <p>This will be {@code null} for static factory methods which will
	 * be derived from the bean class instead.
	 *
	 * @see #getFactoryMethodName()
	 * @see #getBeanClassName()
	 */
	@Nullable
	String getFactoryBeanName();

	/**
	 * Specify a factory method, if any. This method will be invoked with
	 * constructor arguments, or with no arguments if none are specified.
	 * The method will be invoked on the specified factory bean, if any,
	 * or otherwise as a static method on the local bean class.
	 *
	 * @see #setFactoryBeanName
	 * @see #setBeanClassName
	 */
	void setFactoryMethodName(@Nullable String factoryMethodName);

	/**
	 * Return a factory method, if any.
	 *
	 * @see #getFactoryBeanName()
	 * @see #getBeanClassName()
	 */
	@Nullable
	String getFactoryMethodName();

	/**
	 * Return the constructor argument values for this bean.
	 * <p>The returned instance can be modified during bean factory post-processing.
	 *
	 * @return the ConstructorArgumentValues object (never {@code null})
	 */
	ConstructorArgumentValues getConstructorArgumentValues();

	/**
	 * Return if there are constructor argument values defined for this bean.
	 *
	 * @see #getConstructorArgumentValues()
	 * @since 5.0.2
	 */
	default boolean hasConstructorArgumentValues() {
		return !getConstructorArgumentValues().isEmpty();
	}

	/**
	 * Return the property values to be applied to a new instance of the bean.
	 * <p>The returned instance can be modified during bean factory post-processing.
	 *
	 * @return the MutablePropertyValues object (never {@code null})
	 */
	MutablePropertyValues getPropertyValues();

	/**
	 * Return if there are property values defined for this bean.
	 *
	 * @see #getPropertyValues()
	 * @since 5.0.2
	 */
	default boolean hasPropertyValues() {
		return !getPropertyValues().isEmpty();
	}

	/**
	 * Set the name of the initializer method.
	 *
	 * @since 5.1
	 */
	void setInitMethodName(@Nullable String initMethodName);

	/**
	 * Return the name of the initializer method.
	 *
	 * @since 5.1
	 */
	@Nullable
	String getInitMethodName();

	/**
	 * Set the name of the destroy method.
	 *
	 * @since 5.1
	 */
	void setDestroyMethodName(@Nullable String destroyMethodName);

	/**
	 * Return the name of the destroy method.
	 *
	 * @since 5.1
	 */
	@Nullable
	String getDestroyMethodName();

	/**
	 * Set the role hint for this {@code BeanDefinition}. The role hint
	 * provides the frameworks as well as tools an indication of
	 * the role and importance of a particular {@code BeanDefinition}.
	 *
	 * @see #ROLE_APPLICATION
	 * @see #ROLE_SUPPORT
	 * @see #ROLE_INFRASTRUCTURE
	 * @since 5.1
	 */
	void setRole(int role);

	/**
	 * 获取此{@code BeanDefinition}的角色提示。角色提示
	 * 提供框架以及工具来指示
	 * 特定{@code BeanDefinition}的作用和重要性。
	 *
	 * @see #ROLE_APPLICATION
	 * @see #ROLE_SUPPORT
	 * @see #ROLE_INFRASTRUCTURE
	 */
	int getRole();

	/**
	 * 设置当前Bean定义的刻度描述
	 *
	 * @since 5.1
	 */
	void setDescription(@Nullable String description);

	/**
	 * 返回当前 bean 定义的可读的描述。
	 */
	@Nullable
	String getDescription();


	// Read-only attributes

	/**
	 * 返回bean 定义的解析类型，
	 * 基于bean类或其他特定元数据。
	 * <p>这通常在运行时合并的 bean 定义上完全解决
	 * 但不一定在配置时定义实例上。
	 *
	 * @return 可解析类型（可能是 {@link ResolvableType#NONE}）
	 * @see ConfigurableBeanFactory#getMergedBeanDefinition
	 * @since 5.2
	 */
	ResolvableType getResolvableType();

	/**
	 * 判断是否为单例Bean
	 * 返回这是否是一个 <b>Singleton</b>，具有单个共享实例
	 * 所有调用均返回。
	 *
	 * @参见#SCOPE_SINGLETON
	 */
	boolean isSingleton();

	/**
	 * 判断是否为原型Bean
	 * 返回这是否是一个<b>Prototype</b>，具有独立的实例
	 * 每次调用都会返回。
	 *
	 * @see #SCOPE_PROTOTYPE
	 * @since 3.0
	 */
	boolean isPrototype();

	/**
	 * 返回此bean是否是“Abstract”的，即不打算被实例化
	 * 本身，而只是充当具体子 bean 定义的父级。
	 */
	boolean isAbstract();

	/**
	 * 返回此bean定义的资源的描述
	 * 来自（为了在出现错误时显示上下文）。
	 */
	@Nullable
	String getResourceDescription();

	/**
	 * 返回原始 BeanDefinition，如果没有则返回 {@code null}。
	 * <p>允许检索修饰的 bean 定义（如果有）。
	 * <p>请注意，此方法返回直接发起者。迭代通过
	 * 发起者链来查找用户定义的原始 BeanDefinition。
	 */
	@Nullable
	BeanDefinition getOriginatingBeanDefinition();

}

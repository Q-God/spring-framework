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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 共享 bean 实例的通用注册表，实现了 SingletonBeanRegistry 接口。
 * 允许注册应该为注册表的所有调用者共享的单例实例，可以通过 bean 名称获取。
 *
 * <p>还支持注册 DisposableBean 实例（可能与已注册的单例相对应），
 * 这些实例将在注册表关闭时销毁。还可以注册 bean 之间的依赖关系，以确保适当的关闭顺序。
 *
 * <p>这个类主要用作 BeanFactory 实现的基类，将单例 bean 实例的通用管理抽取出来。
 * 请注意，ConfigurableBeanFactory 接口扩展了 SingletonBeanRegistry 接口。
 *
 * <p>请注意，与 AbstractBeanFactory 和 DefaultListableBeanFactory 不同，
 * 这个类既不假设 bean 定义概念，也不假设特定的 bean 实例创建过程。
 * 也可以作为嵌套助手来使用委托。
 *
 * @author Juergen Hoeller
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 * @since 2.0
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/**
	 * 保留的最大抑制异常数量。
	 * <p>在异常链中，抑制异常是指在处理其他异常时发生的额外异常，通常是由于底层资源关闭或释放而引起的。
	 * 为了避免信息丢失，Java 7 引入了抑制异常的概念，允许将一个异常添加到另一个异常的抑制异常列表中。
	 * 这个常量指定了在抑制异常列表中要保留的最大异常数量。如果抑制异常的数量超过了这个限制，超出部分将被丢弃，
	 * 以避免填充整个堆栈跟踪或引发内存问题。
	 */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/**
	 * 一级缓存 单例对象的缓存：从 bean 名称到 bean 实例的映射。
	 * <p>这个缓存用于存储已经创建的单例对象，其中键是 bean 的名称，值是对应的单例实例。
	 * 使用 ConcurrentHashMap 来保证线程安全，并且允许并发读取和写入。
	 * 初始化时使用了默认的初始容量 256，以及默认的加载因子和并发级别。
	 * 这个缓存的作用是提高单例对象的访问效率，避免重复创建相同的单例对象。
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * 三级缓存  在创建时注册的单例工厂的缓存：从 bean 名称到 ObjectFactory 的映射。
	 * <p>这个缓存用于存储创建单例对象的工厂方法，其中键是 bean 的名称，值是对应的 ObjectFactory。
	 * 当需要获取某个单例对象时，如果在 singletonObjects 缓存中没有找到对应的实例，
	 * 就会通过对应的 ObjectFactory 创建一个新的实例，并将其放入 singletonObjects 缓存中。
	 * 这个缓存的作用是延迟创建单例对象，只有在需要时才会进行创建，可以节省内存空间和初始化时间。
	 * 使用 ConcurrentHashMap 来保证线程安全，并且允许并发读取和写入。
	 * 初始化时使用了默认的初始容量 16，以及默认的加载因子和并发级别。
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>(16);

	/**
	 * 单例创建/注册的自定义回调函数的缓存。
	 * <p>这个缓存用于存储单例对象创建或注册时的自定义回调函数，其中键是 bean 的名称，
	 * 值是对应的 Consumer<Object> 回调函数。当单例对象创建完成后，可以通过这些回调函数
	 * 进行额外的处理或操作，例如初始化、依赖注入等。
	 * 使用 ConcurrentHashMap 来保证线程安全，并且允许并发读取和写入。
	 * 初始化时使用了默认的初始容量 16，以及默认的加载因子和并发级别。
	 */
	private final Map<String, Consumer<Object>> singletonCallbacks = new ConcurrentHashMap<>(16);

	/**
	 * 二级缓存  提前暴露的单例对象的缓存：bean 名称到 bean 实例的映射。
	 * <p>这个缓存用于存储在创建完成之前已经提前暴露的单例对象，其中键是 bean 的名称，
	 * 值是对应的单例对象实例。在 Spring 的初始化过程中，一些单例对象可能在依赖注入完成之前
	 * 就被其他对象引用，这些对象就会被提前暴露到该缓存中，以避免循环依赖的问题。
	 * 使用 ConcurrentHashMap 来保证线程安全，并且允许并发读取和写入。
	 * 初始化时使用了默认的初始容量 16，以及默认的加载因子和并发级别。
	 */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * 已注册的单例对象的集合，按注册顺序包含了 bean 的名称。
	 * <p>这个集合用于存储已经注册的单例对象的名称，并且保持了注册的顺序。
	 * 在注册单例对象时，会将其名称添加到该集合中，以便跟踪已注册的单例对象，并且可以按照注册的顺序进行迭代或其他操作。
	 * 使用了 synchronizedSet 来确保线程安全，并且使用了 LinkedHashSet 来保持注册的顺序。
	 * 初始容量为 256，这个值可以根据实际场景进行调整以提高性能。
	 */
	private final Set<String> registeredSingletons = Collections.synchronizedSet(new LinkedHashSet<>(256));

	/**
	 * 单例对象锁，用于控制对单例对象的并发访问。
	 * <p>这个锁用于保护对单例对象的并发访问，以确保在多线程环境中对单例对象的操作是线程安全的。
	 * 使用了 ReentrantLock 实现，可以支持更灵活的锁获取和释放操作。
	 * 使用了 final 修饰，表示锁对象在初始化后不能再被修改。
	 */
	private final Lock singletonLock = new ReentrantLock();

	/**
	 * 当前正在创建的 bean 的名称集合。
	 * <p>这个集合用于跟踪正在创建的 bean 的名称，在 bean 创建过程中，会将正在创建的 bean 的名称添加到这个集合中，
	 * 当创建完成后再将其移除。这样可以防止循环依赖的问题。
	 * 使用了 ConcurrentHashMap 的 newKeySet() 方法创建一个线程安全的 Set 集合，并设置了初始容量为 16。
	 * 使用了 final 修饰，表示集合在初始化后不能再被修改。
	 */
	private final Set<String> singletonsCurrentlyInCreation = ConcurrentHashMap.newKeySet(16);

	/**
	 * 当前被排除在创建检查之外的 bean 的名称集合。
	 * <p>这个集合用于存储被排除在创建检查之外的 bean 的名称。在某些情况下，可能会有一些特殊的 bean，
	 * 它们不需要进行循环依赖的检查，此时就会将这些 bean 的名称添加到这个集合中进行排除。
	 * 使用了 ConcurrentHashMap 的 newKeySet() 方法创建一个线程安全的 Set 集合，并设置了初始容量为 16。
	 * 使用了 final 修饰，表示集合在初始化后不能再被修改。
	 */
	private final Set<String> inCreationCheckExclusions = ConcurrentHashMap.newKeySet(16);

	/**
	 * 用于跟踪当前正在创建单例对象的线程。
	 * <p>这个字段使用了 volatile 关键字修饰，表示对它的读写操作是原子的，可以保证在多线程环境下的可见性，
	 * 即一个线程对它的修改会立即被其他线程看到。
	 * <p>在创建单例对象期间，会将当前线程赋值给这个字段，以便在后续的操作中检测循环依赖。
	 * 如果当前没有线程正在创建单例对象，则这个字段的值为 null。
	 * <p>使用了 @Nullable 注解，表示该字段可以为 null。
	 */
	@Nullable
	private volatile Thread singletonCreationThread;

	/**
	 * 用于标识当前是否处于销毁单例对象的过程中。
	 * <p>这个字段使用了 volatile 关键字修饰，表示对它的读写操作是原子的，可以保证在多线程环境下的可见性，
	 * 即一个线程对它的修改会立即被其他线程看到。
	 * <p>在销毁单例对象期间，会将这个字段设置为 true，以避免重入问题。
	 * <p>如果当前不是在销毁单例对象的过程中，则这个字段的值为 false。
	 */
	private volatile boolean singletonsCurrentlyInDestruction = false;

	/**
	 * 用于收集抑制的异常集合，可用于关联相关的原因。
	 * <p>这个字段标识了一个可能为空的异常集合，用于存储与当前异常相关的其他异常。
	 * 当一个异常被捕获处理时，有时候可能会伴随着其他异常的发生，这些异常被称为抑制异常。
	 * <p>使用 @Nullable 注解表示这个字段可能为空，即可能没有抑制的异常存在。
	 */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/**
	 * 一次性可销毁的 Bean 实例集合：从 Bean 名称到一次性实例的映射。
	 * <p>这个字段用于存储需要销毁的 Bean 实例，以便在容器关闭时执行销毁操作。
	 * <p>使用 LinkedHashMap 是为了保持注册的顺序。
	 */
	private final Map<String, DisposableBean> disposableBeans = new LinkedHashMap<>();

	/**
	 * 包含 Bean 名称之间的映射关系：从 Bean 名称到 Bean 包含的 Bean 名称集合的映射。
	 * <p>这个字段用于存储 Bean 之间的包含关系，一个 Bean 可能包含其他 Bean。
	 * <p>使用 ConcurrentHashMap 是为了线程安全，并且初始化容量为 16。
	 */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/**
	 * 包含依赖关系的 Bean 名称之间的映射关系：从 Bean 名称到依赖于该 Bean 的 Bean 名称集合的映射。
	 * <p>这个字段用于存储 Bean 之间的依赖关系，一个 Bean 可能会依赖于其他 Bean。
	 * <p>使用 ConcurrentHashMap 是为了线程安全，并且初始化容量为 64。
	 */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * 包含依赖关系的 Bean 名称之间的映射关系：从 Bean 名称到该 Bean 的依赖名称集合的映射。
	 * <p>这个字段用于存储 Bean 之间的依赖关系，一个 Bean 可能依赖于其他 Bean。
	 * <p>使用 ConcurrentHashMap 是为了线程安全，并且初始化容量为 64。
	 */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	/**
	 * 注册一个单例对象到注册表中。
	 * 被调用以公开新注册/创建的单例实例。
	 *
	 * @param beanName        bean 的名称
	 * @param singletonObject 单例对象
	 * @throws IllegalStateException 如果已经存在同名的单例对象
	 */
	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		// 断言 bean 名称和单例对象不为空，否则抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		// 获取 singletonLock，并加锁以确保线程安全
		this.singletonLock.lock();
		try {
			// 将单例对象添加到单例池中
			addSingleton(beanName, singletonObject);
		} finally {
			// 释放锁
			this.singletonLock.unlock();
		}
	}

	/**
	 * 将给定的单例对象添加到单例池中。
	 * <p>用于公开新注册/创建的单例对象。
	 *
	 * @param beanName        bean的名称
	 * @param singletonObject 单例对象
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		// 将单例对象放入 singletonObjects 中，并返回原先与 beanName 关联的对象（如果存在）
		Object oldObject = this.singletonObjects.putIfAbsent(beanName, singletonObject);
		// 如果原先与 beanName 关联的对象已存在，则抛出 IllegalStateException 异常
		if (oldObject != null) {
			throw new IllegalStateException("Could not register object [" + singletonObject +
					"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
		}
		//删除三级缓存singletonFactories、二级缓存earlySingletonObjects中的对象并添加到一级缓存中去
		this.singletonFactories.remove(beanName);
		this.earlySingletonObjects.remove(beanName);
		this.registeredSingletons.add(beanName);

		// 获取 beanName 对应的回调函数，如果存在则调用回调函数
		Consumer<Object> callback = this.singletonCallbacks.get(beanName);
		if (callback != null) {
			callback.accept(singletonObject);
		}
	}

	/**
	 * 添加指定的单例工厂用于构建必要的单例对象。
	 * 用于早期暴露的目的，例如解析循环引用。
	 *
	 * @param beanName         bean 的名称
	 * @param singletonFactory 单例对象的工厂
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		// 断言单例工厂不为空
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		// 将单例工厂添加到三级缓存 singletonFactories 、一级缓存 registeredSingletons中，从二级缓存中移除
		this.singletonFactories.put(beanName, singletonFactory);
		this.earlySingletonObjects.remove(beanName);
		this.registeredSingletons.add(beanName);
	}

	/**
	 * 添加用于单例对象的回调函数。
	 *
	 * @param beanName          bean 的名称
	 * @param singletonConsumer 单例对象的消费者
	 */
	@Override
	public void addSingletonCallback(String beanName, Consumer<Object> singletonConsumer) {
		// 将单例对象的消费者添加到 singletonCallbacks 中
		this.singletonCallbacks.put(beanName, singletonConsumer);
	}

	/**
	 * 获取指定名称的单例对象。
	 *
	 * @param beanName bean 的名称
	 * @return 指定名称的单例对象，如果不存在则返回 null
	 */
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * 返回给定名称下注册的（原始）单例对象。
	 * <p>检查已经实例化的单例对象，并允许提前引用当前正在创建的单例对象（解决循环引用）。
	 *
	 * @param beanName            要查找的 bean 的名称
	 * @param allowEarlyReference 是否允许暴露早期对象  通过该参数可以控制是否能够解决循环依赖的.
	 * @return 这里可能返回一个null（IOC容器加载单实例bean的时候,第一次进来是返回null）
	 * 也有可能返回一个单例对象(IOC容器加载了单实例了,第二次来获取当前的Bean)
	 * 也可能返回一个早期对象(用于解决循环依赖问题)
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {

		//第一步:我们尝试去一级缓存(单例缓存池中去获取对象,一般情况从该map中获取的对象是直接可以使用的)
		//IOC容器初始化加载单实例bean的时候第一次进来的时候 该map中一般返回空
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果一级缓存不存在实例并且该bean标记为正在创建
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			//尝试去二级缓存中获取对象(二级缓存中的对象是一个早期对象)
			//何为早期对象:就是bean刚刚调用了构造方法，还来不及给bean的属性进行赋值的对象(纯净态)就是早期对象
			singletonObject = this.earlySingletonObjects.get(beanName);
			//  二级缓存中也没有获取到对象,allowEarlyReference为true(参数是有上一个方法传递进来的true)
			if (singletonObject == null && allowEarlyReference) {
				// 尝试获取锁，避免在原始创建线程之外提前调用单例
				if (!this.singletonLock.tryLock()) {
					// Avoid early singleton inference outside of original creation thread.
					return null;
				}
				try {
					// 再再次尝试从一级缓存中去拿，如果还是没拿到则尝试去二级缓存中
					singletonObject = this.singletonObjects.get(beanName);
					//一级缓存仍未拿到
					if (singletonObject == null) {
						//尝试从二级缓存中去拿
						singletonObject = this.earlySingletonObjects.get(beanName);
						//二级缓存还是空
						if (singletonObject == null) {
							//直接从三级缓存中获取 ObjectFactory对象 这个对接就是用来解决循环依赖的关键所在
							//在ioc后期的过程中,当bean调用了构造方法的时候,把早期对象包裹成一个ObjectFactory
							//暴露到三级缓存中
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							//三级缓存中获取的对象不为空
							if (singletonFactory != null) {
								//在这里通过暴露的ObjectFactory 包装对象中,通过调用他的getObject()来获取我们的早期对象
								//在这个环节中会调用到 getEarlyBeanReference()来进行后置处理
								singletonObject = singletonFactory.getObject();
								//ObjectFactory 包装对象从三级缓存中删除掉
								if (this.singletonFactories.remove(beanName) != null) {
									//把早期对象放置在二级缓存,
									this.earlySingletonObjects.put(beanName, singletonObject);
								} else {
									//否则再次从一级缓存中获取
									singletonObject = this.singletonObjects.get(beanName);
								}
							}
						}
					}
				} finally {
					// 释放锁
					this.singletonLock.unlock();
				}
			}
		}
		//返回单例实例
		return singletonObject;
	}

	/**
	 * 返回在给定名称下注册的（原始）单例对象，如果尚未注册，则创建并注册一个新的。
	 *
	 * @param beanName         Bean的名称
	 * @param singletonFactory 用于延迟创建单例的ObjectFactory（必要时）
	 * @return 注册的单例对象
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		// 确保 beanName 不为 null
		Assert.notNull(beanName, "Bean name must not be null");
		// 检查当前线程是否允许持有单例锁
		boolean acquireLock = isCurrentThreadAllowedToHoldSingletonLock();
		// 尝试获取单例锁
		boolean locked = (acquireLock && this.singletonLock.tryLock());
		try {
			// 从一级缓存中获取单例对象
			Object singletonObject = this.singletonObjects.get(beanName);
			//从一级缓存中没拿到
			if (singletonObject == null) {
				// 如果当前线程允许持有单例锁
				if (acquireLock) {
					// 如果成功获取到单例锁
					if (locked) {
						//单例创建线程为当前线程
						this.singletonCreationThread = Thread.currentThread();
					} else {
						Thread threadWithLock = this.singletonCreationThread;
						if (threadWithLock != null) {
							// 另一个线程正在忙于单例工厂回调，可能被阻塞。
							// 自 Spring 6.2 开始的新策略：在单例锁之外处理给定的单例 bean。
							// 仍然保证了线程安全性，只是在触发其他 bean 的创建时存在冲突的风险，因为它们可能是当前 bean 的依赖项。
							if (logger.isInfoEnabled()) {
								//打印info级别日志
								logger.info("Creating singleton bean '" + beanName + "' in thread \"" +
										Thread.currentThread().getName() + "\" while thread \"" + threadWithLock.getName() +
										"\" holds singleton lock for other beans " + this.singletonsCurrentlyInCreation);
							}
						} else {
							// 单例锁当前由某些其他注册方法持有 -> 等待
							this.singletonLock.lock();
							locked = true;
							/// 单例对象可能在此期间出现。从一级缓存中获取单例对象
							singletonObject = this.singletonObjects.get(beanName);
							//拿到了直接返回
							if (singletonObject != null) {
								return singletonObject;
							}
						}
					}
				}
				// 如果当前单例工厂对象正在销毁中，则抛出异常
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
									"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				// 打印debug级别日志
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				//在创建单例对象之前执行相应的处理
				//标记当前的bean马上就要被创建了
				//singletonsCurrentlyInCreation 在这里会把beanName加入进来，若第二次循环依赖（构造器注入会抛出异常）
				beforeSingletonCreation(beanName);
				//标记是否为新创建的单例Bean
				boolean newSingleton = false;
				//标记是否记录抑制异常
				boolean recordSuppressedExceptions = (locked && this.suppressedExceptions == null);
				//如果为空，创建抑制异常集合
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				// 设置当前线程为单例创建线程
				this.singletonCreationThread = Thread.currentThread();
				try {
					// 初始化 bean
					// 这个过程其实是调用 createBean() 方法
					singletonObject = singletonFactory.getObject();
					//标记这个Bean是新创建的
					newSingleton = true;
				} catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					//在此期间是否隐式创建了单例对象 -> 如果是，则继续处理它，因为异常指该状态。
					singletonObject = this.singletonObjects.get(beanName);
					//一级缓存中没有，抛出异常
					if (singletonObject == null) {
						throw ex;
					}
				} catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						//遍历抑制异常集合，添加相关原因
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				} finally {
					//清理singletonCreationThread线程
					this.singletonCreationThread = null;
					//记录抑制异常集合置空，复用
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					//后置处理
					//主要做的事情就是把singletonsCurrentlyInCreation标记正在创建的bean从集合中移除
					afterSingletonCreation(beanName);
				}
				//是新建的单例Bean，添加到一级缓存中去
				if (newSingleton) {
					addSingleton(beanName, singletonObject);
				}
			}
			//返回单例Bean
			return singletonObject;
		} finally {
			if (locked) {
				//释放单例锁
				this.singletonLock.unlock();
			}
		}
	}

	/**
	 * Determine whether the current thread is allowed to hold the singleton lock.
	 * <p>By default, any thread may acquire and hold the singleton lock, except
	 * background threads from {@link DefaultListableBeanFactory#setBootstrapExecutor}.
	 *
	 * @since 6.2
	 */
	protected boolean isCurrentThreadAllowedToHoldSingletonLock() {
		return true;
	}

	/**
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 *
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
			this.suppressedExceptions.add(ex);
		}
	}

	/**
	 * Remove the bean with the given name from the singleton registry, either on
	 * regular destruction or on cleanup after early exposure when creation failed.
	 *
	 * @param beanName the name of the bean
	 */
	protected void removeSingleton(String beanName) {
		this.singletonObjects.remove(beanName);
		this.singletonFactories.remove(beanName);
		this.earlySingletonObjects.remove(beanName);
		this.registeredSingletons.remove(beanName);
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		return StringUtils.toStringArray(this.registeredSingletons);
	}

	@Override
	public int getSingletonCount() {
		return this.registeredSingletons.size();
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		} else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 *
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(@Nullable String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 *
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 *
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 *
	 * @param beanName the name of the bean
	 * @param bean     the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 *
	 * @param containedBeanName  the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 *
	 * @param beanName          the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		String canonicalName = canonicalName(beanName);

		synchronized (this.dependentBeanMap) {
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 *
	 * @param beanName          the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		String canonicalName = canonicalName(beanName);
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans == null || dependentBeans.isEmpty()) {
			return false;
		}
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		if (alreadySeen == null) {
			alreadySeen = new HashSet<>();
		}
		alreadySeen.add(beanName);
		for (String transitiveDependency : dependentBeans) {
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 *
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 *
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 *
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		this.singletonsCurrentlyInDestruction = true;

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		this.singletonLock.lock();
		try {
			clearSingletonCache();
		} finally {
			this.singletonLock.unlock();
		}
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 *
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		this.singletonObjects.clear();
		this.singletonFactories.clear();
		this.earlySingletonObjects.clear();
		this.registeredSingletons.clear();
		this.singletonsCurrentlyInDestruction = false;
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 *
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Destroy the corresponding DisposableBean instance.
		// This also triggers the destruction of dependent beans.
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);

		// destroySingletons() removes all singleton instances at the end,
		// leniently tolerating late retrieval during the shutdown phase.
		if (!this.singletonsCurrentlyInDestruction) {
			// For an individual destruction, remove the registered instance now.
			// As of 6.2, this happens after the current bean's destruction step,
			// allowing for late bean retrieval by on-demand suppliers etc.
			this.singletonLock.lock();
			try {
				removeSingleton(beanName);
			} finally {
				this.singletonLock.unlock();
			}
		}
	}

	/**
	 * 销毁给定的bean。必须先销毁依赖于给定bean的bean，然后再销毁bean本身。不应抛出任何异常。
	 *
	 * @param beanName 要销毁的bean的名称
	 * @param bean     要销毁的bean实例
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// 首先触发销毁依赖于当前bean的其他bean...
		Set<String> dependentBeanNames;
		synchronized (this.dependentBeanMap) {
			// 在完全同步的情况下，以确保获得一个不相关的Set
			dependentBeanNames = this.dependentBeanMap.remove(beanName);
		}
		//当前Bean依赖其他的bean 依赖集合不为空，循环销毁依赖的单例Bean
		if (dependentBeanNames != null) {
			//trace日志
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependentBeanNames);
			}
			// 逐个销毁依赖于当前bean的其他bean
			for (String dependentBeanName : dependentBeanNames) {
				destroySingleton(dependentBeanName);
			}
		}

		// 实际销毁当前bean...
		if (bean != null) {
			try {
				//销毁Bean
				bean.destroy();
			} catch (Throwable ex) {
				// 捕获异常并记录警告日志，但不抛出异常
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// 触发销毁当前bean包含的其他bean...
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// 在完全同步的情况下，以确保获得一个不相关的Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			// 逐个销毁当前bean包含的其他bean
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		//销毁dependentBeanMap 中 Bean的依赖
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				// 逐个销毁当前bean包含的其他bean
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// 移除已销毁 bean 的的依赖信息
		this.dependenciesForBeanMap.remove(beanName);
	}


	/**
	 * 6.2版本已经弃用
	 * 将单例互斥体暴露给子类和外部合作者。 如果子类执行任何类型的扩展单例创建阶段，
	 * 它们应该在给定的对象上同步。特别是子类不应该在单例创建中使用它们自己的互斥锁，
	 * 以避免在惰性初始化情况下潜在的死锁。
	 *
	 * @return
	 */
	@Deprecated(since = "6.2")
	@Override
	public final Object getSingletonMutex() {
		return new Object();
	}

}

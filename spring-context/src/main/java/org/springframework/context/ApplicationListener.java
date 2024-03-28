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

package org.springframework.context;

import java.util.EventListener;
import java.util.function.Consumer;

/**
 * 应用程序事件监听器实现的接口。
 * <p>
 * 基于观察者设计模式的标准 {@link java.util.EventListener} 接口。
 * <p>
 * {@code ApplicationListener} 可以通用地声明其感兴趣的事件类型。
 * 当注册到 Spring {@code ApplicationContext} 时，事件将相应地进行过滤，
 * 只有匹配的事件对象才会调用侦听器。
 *
 * @param <E> 要监听的特定 {@code ApplicationEvent} 子类
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.context.ApplicationEvent
 * @see org.springframework.context.event.ApplicationEventMulticaster
 * @see org.springframework.context.event.SmartApplicationListener
 * @see org.springframework.context.event.GenericApplicationListener
 * @see org.springframework.context.event.EventListener
 */
@FunctionalInterface
public interface ApplicationListener<E extends ApplicationEvent> extends EventListener {

	/**
	 * 处理应用程序事件。
	 *
	 * @param event 要响应的事件
	 */
	void onApplicationEvent(E event);

	/**
	 * 返回此Listener侦听器是否支持异步执行。
	 *
	 * @return 如果此侦听器实例可以根据广播器配置（默认情况下）异步执行，
	 * 则返回 {@code true}；如果需要立即在发布事件的原始线程中运行，则返回 {@code false}
	 * @see org.springframework.context.event.SimpleApplicationEventMulticaster#setTaskExecutor
	 * @since 6.1
	 */
	default boolean supportsAsyncExecution() {
		return true;
	}


	/**
	 * 为给定的消费者创建一个新的 {@code ApplicationListener}。
	 *
	 * @param consumer 事件载荷消费者
	 * @param <T>      事件载荷的类型
	 * @return 对应的 {@code ApplicationListener} 实例
	 * @see PayloadApplicationEvent
	 * @since 5.3
	 */
	static <T> ApplicationListener<PayloadApplicationEvent<T>> forPayload(Consumer<T> consumer) {
		return event -> consumer.accept(event.getPayload());
	}

}

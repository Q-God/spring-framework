/*
 * Copyright 2002-2018 the original author or authors.
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

/**
 * 定义启动/停止生命周期控制方法的通用接口。
 * 典型用例是控制异步处理。
 * <b>注意：此接口并不暗示特定的自动启动语义。
 * 考虑为此目的实施 {@link SmartLifecycle}。</b>
 *
 * <p>可以由两个组件实现（通常是定义在
 * Spring 上下文）和容器（通常是 Spring {@link ApplicationContext}
 * 本身）。 容器将向所有组件传播启动/停止信号
 * 适用于每个容器，例如 用于运行时停止/重新启动的场景。
 *
 * <p>可用于直接调用或通过 JMX 进行管理操作。
 * 在后一种情况下，{@link org.springframework.jmx.export.MBeanExporter}
 * 通常用一个来定义
 * {@link org.springframework.jmx.export.assembler.InterfaceBasedMBeanInfoAssembler}，
 * 限制活动控制组件对生命周期的可见性
 * 界面。
 *
 * <p>请注意，当前的 {@code Lifecycle} 接口仅支持
 * <b>顶级单例 bean</b>。 在任何其他组件上，{@code Lifecycle}
 * 接口将保持未被检测到并因此被忽略。 另请注意，扩展
 * {@link SmartLifecycle} 接口提供了与
 * 应用程序上下文的启动和关闭阶段。
 *
 * @author Juergen Hoeller
 * @see SmartLifecycle
 * @see ConfigurableApplicationContext
 * @see org.springframework.jms.listener.AbstractMessageListenerContainer
 * @see org.springframework.scheduling.quartz.SchedulerFactoryBean
 * @since 2.0
 */
public interface Lifecycle {

	/**
	 * 启动该组件。
	 * <p>如果组件已经在运行，则不应引发异常。
	 * <p>对于容器，这会将启动信号传播到所有容器
	 * 适用的组件。
	 *
	 * @see SmartLifecycle#isAutoStartup()
	 */
	void start();

	/**
	 * 停止该组件，通常以同步方式，以便该组件
	 * 此方法返回后完全停止。 考虑实施{@link SmartLifecycle}
	 * 当需要异步停止行为时，及其 {@code stop(Runnable)} 变体。
	 * <p>请注意，不保证在销毁之前发出此停止通知：
	 * 在定期关闭时，{@code Lifecycle} beans 将首先收到停止通知
	 * 在传播一般销毁回调之前； 然而，在热
	 * 在上下文的生命周期内或在中止刷新尝试时刷新，给定的 bean
	 * 将调用 destroy 方法，而不预先考虑停止信号。
	 * <p>如果组件未运行（尚未启动），则不应引发异常。
	 * <p>对于容器，这会将停止信号传播到所有组件
	 * 适用。
	 *
	 * @see SmartLifecycle#stop(Runnable)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	void stop();

	/**
	 * 检查该组件当前是否正在运行。
	 * <p>对于容器，仅当 <i>all</i> 时才会返回 {@code true}
	 * 适用的组件当前正在运行。
	 *
	 * @return 组件当前是否正在运行
	 */
	boolean isRunning();

}

/*
 * Copyright 2002-2019 the original author or authors.
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

import org.springframework.lang.Nullable;

import java.util.Locale;

/**
 * 消息解析策略接口，支持参数化
 * 以及此类消息的国际化。
 *
 * <p>Spring 为生产环境提供了两种开箱即用的实现：
 * <ul>
 * <li>{@link org.springframework.context.support.ResourceBundleMessageSource}：已构建
 * 在标准 {@link java.util.ResourceBundle} 之上，共享其局限性。
 * <li>{@link org.springframework.context.support.ReloadableResourceBundleMessageSource}：
 * 高度可配置，特别是在重新加载消息定义方面。
 * </ul>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.context.support.ResourceBundleMessageSource
 * @see org.springframework.context.support.ReloadableResourceBundleMessageSource
 */
public interface MessageSource {

	/**
	 * 尝试获取该消息。 如果没有找到消息，则返回默认消息。
	 *
	 * @param code           要查找的消息代码，例如 'calculator.noRateSet'。
	 *                       鼓励 MessageSource 用户将消息名称基于合格的类
	 *                       或包名称，避免潜在的冲突并确保最大程度的清晰度。
	 * @param args           一个参数数组，将在其中填充参数
	 *                       消息（消息中的参数类似于“{0}”、“{1,date}”、“{2,time}”），
	 *                       或 {@code null} 如果没有
	 * @param defaultMessage 查找失败时返回的默认消息
	 * @param locale         进行查找的语言环境
	 * @return 如果查找成功则返回已解析的消息，否则返回 作为参数传递的默认消息（可能是{@code null}）
	 * @see #getMessage(MessageSourceResolvable, Locale)
	 * @see java.text.MessageFormat
	 */
	@Nullable
	String getMessage(String code, @Nullable Object[] args, @Nullable String defaultMessage, Locale locale);

	/**
	 * 尝试获取该消息。 如果找不到该消息，则将其视为错误。
	 *
	 * @param code   要查找的消息代码，例如 '计算器.noRateSet'。
	 *               鼓励 MessageSource 用户将消息名称基于合格的类
	 *               或包名称，避免潜在的冲突并确保最大程度的清晰度。
	 * @param args   一个参数数组，将在其中填充参数
	 *               消息（消息中的参数类似于“{0}”、“{1,date}”、“{2,time}”），
	 *               或 {@code null} 如果没有
	 * @param locale 进行查找的区域设置
	 * @return 已解析的消息（绝不是 {@code null}）
	 * 如果没有找到相应的消息则@抛出NoSuchMessageException
	 * @see #getMessage(MessageSourceResolvable, Locale)
	 * @see java.text.MessageFormat
	 */
	String getMessage(String code, @Nullable Object[] args, Locale locale) throws NoSuchMessageException;

	/**
	 * 尝试使用包含在消息中的所有属性来解析消息
	 * 传入的 {@code MessageSourceResolvable} 参数。
	 * <p>注意：我们必须在此方法上抛出一个 {@code NoSuchMessageException}
	 * 因为在调用此方法时我们无法确定是否
	 * 可解析的 {@code defaultMessage} 属性是否为 {@code null}。
	 *
	 * @param resolvable 存储解析消息所需属性的值对象
	 *                   （可能包括默认消息）
	 * @param locale     进行查找的语言环境
	 * @return 已解决的消息（永远不要{@code null}，因为即使是
	 * {@code MessageSourceResolvable}-提供的默认消息需要非空）
	 * 如果没有找到相应的消息则@抛出NoSuchMessageException
	 * （{@code MessageSourceResolvable} 未提供默认消息）
	 * @see MessageSourceResolvable#getCodes()
	 * @see MessageSourceResolvable#getArguments()
	 * @see MessageSourceResolvable#getDefaultMessage()
	 * @see java.text.MessageFormat
	 */
	String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException;

}

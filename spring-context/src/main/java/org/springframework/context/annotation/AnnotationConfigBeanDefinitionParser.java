/*
 * Copyright 2002-2012 the original author or authors.
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

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.w3c.dom.Element;

import java.util.Set;

/**
 * 用于解析 &lt;context:annotation-config/&gt; 元素的解析器。
 * <p>
 * 这个解析器的作用是为 &lt;context:annotation-config/&gt; 元素注册相关的 BeanPostProcessors。
 *
 * @author Mark Fisher
 * @author Juergen Hoeller
 * @author Christian Dupuis
 * @see AnnotationConfigUtils
 * @since 2.5
 */
public class AnnotationConfigBeanDefinitionParser implements BeanDefinitionParser {

	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		// 提取元素的来源对象
		Object source = parserContext.extractSource(element);

		// 获取所有相关 BeanPostProcessors 的 bean 定义
		Set<BeanDefinitionHolder> processorDefinitions =
				AnnotationConfigUtils.registerAnnotationConfigProcessors(parserContext.getRegistry(), source);

		// 为周围的 <context:annotation-config> 元素注册组件
		CompositeComponentDefinition compDefinition = new CompositeComponentDefinition(element.getTagName(), source);
		parserContext.pushContainingComponent(compDefinition);

		// 将具体的 bean 嵌套在周围的组件中
		for (BeanDefinitionHolder processorDefinition : processorDefinitions) {
			parserContext.registerComponent(new BeanComponentDefinition(processorDefinition));
		}

		// 最后注册组合组件
		parserContext.popAndRegisterContainingComponent();

		return null;
	}

}

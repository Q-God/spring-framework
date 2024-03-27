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

package org.springframework.core.type.classreading;

import org.springframework.asm.ClassReader;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;

/**
 * 基于 ASM 的 {@link org.springframework.asm.ClassReader} 的 {@link MetadataReader} 实现。
 *
 * @author Juergen Hoeller
 * @author Costin Leau
 * @since 2.5
 */
final class SimpleMetadataReader implements MetadataReader {

	// 定义跳过解析选项，以提高性能
	private static final int PARSING_OPTIONS =
			(ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

	// 存储资源引用
	private final Resource resource;

	// 存储注解元数据
	private final AnnotationMetadata annotationMetadata;

	// 构造函数，接收资源和类加载器作为参数
	SimpleMetadataReader(Resource resource, @Nullable ClassLoader classLoader) throws IOException {
		// 创建一个简单的注解元数据读取访问者
		SimpleAnnotationMetadataReadingVisitor visitor = new SimpleAnnotationMetadataReadingVisitor(classLoader);
		// 使用资源的类读取器解析类文件，并将结果传递给访问者
		getClassReader(resource).accept(visitor, PARSING_OPTIONS);
		// 存储资源和注解元数据
		this.resource = resource;
		this.annotationMetadata = visitor.getMetadata();
	}

	// 根据给定的资源获取类读取器
	private static ClassReader getClassReader(Resource resource) throws IOException {
		try (InputStream is = resource.getInputStream()) {
			try {
				// 创建并返回类读取器
				return new ClassReader(is);
			} catch (IllegalArgumentException ex) {
				// 捕获异常并抛出类格式异常，指示类文件解析失败
				throw new ClassFormatException("ASM ClassReader failed to parse class file - " +
						"probably due to a new Java class file version that is not supported yet. " +
						"Consider compiling with a lower '-target' or upgrade your framework version. " +
						"Affected class: " + resource, ex);
			}
		}
	}


	// 获取资源
	@Override
	public Resource getResource() {
		return this.resource;
	}

	// 获取类元数据
	@Override
	public ClassMetadata getClassMetadata() {
		return this.annotationMetadata;
	}

	// 获取注解元数据
	@Override
	public AnnotationMetadata getAnnotationMetadata() {
		return this.annotationMetadata;
	}

}

/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * <resultMap/>对应实体，对应的是一整块的
 * <resultMap id="detailedBlogResultMap" type="Blog">
 * <constructor>
 * <idArg column="blog_id" javaType="int"/>
 * </constructor>
 * <result property="title" column="blog_title"/>
 * <association property="author" javaType="Author">
 * <id property="id" column="author_id"/>
 * <result property="username" column="author_username"/>
 * <result property="password" column="author_password"/>
 * <result property="email" column="author_email"/>
 * <result property="bio" column="author_bio"/>
 * <result property="favouriteSection" column="author_favourite_section"/>
 * </association>
 * <collection property="posts" ofType="Post">
 * <id property="id" column="post_id"/>
 * <result property="subject" column="post_subject"/>
 * <association property="author" javaType="Author"/>
 * <collection property="comments" ofType="Comment">
 * <id property="id" column="comment_id"/>
 * </collection>
 * <collection property="tags" ofType="Tag" >
 * <id property="id" column="tag_id"/>
 * </collection>
 * <discriminator javaType="int" column="draft">
 * <case value="1" resultType="DraftPost"/>
 * </discriminator>
 * </collection>
 * </resultMap>
 * 需要注意和RequestMapping的却别，RequestMapping对应几乎出了<discriminator/>
 * 的所有一个< xxxx/>，而RequestMap中有多个RequestMapping
 *
 * @author Clinton Begin
 */
public class ResultMap {

	private Configuration configuration;

	// 对应<resultMap>的id属性
	private String id;
	// 对应<resultMap>的type属性
	private Class<?> type;
	// 对应除<discriminator>元素外的所有属性映射关系
	private List<ResultMapping> resultMappings;
	// 对应所有属性映射中带有ID标志的映射关系，包括<id>元素和<constructor>的<idArg>子元素
	private List<ResultMapping> idResultMappings;
	// 对应所有属性映射中带有Constructor标志的映射关系，包括<constructor>所有子元素,
	//一个<resultMap/>中只可能有一个<constructor/>
	private List<ResultMapping> constructorResultMappings;
	// 对应所有属性映射中不带有Constructor标志的映射关系
	private List<ResultMapping> propertyResultMappings;
	//数据库的字段集合
	private Set<String> mappedColumns;
	// 对应所有属性映射中的column属性的集合
	private Set<String> mappedProperties;
	// 鉴别器，对应<discriminator>元素
	private Discriminator discriminator;
	// 是否含有嵌套的结果映射，
	// 如果某个属性映射存在resultMap属性，且不存在resultSet属性，则为true
	private boolean hasNestedResultMaps;
	// 是否含有嵌套查询，
	// 如果某个属性映射存在select属性，则为true
	private boolean hasNestedQueries;
	// 是否开启自动映射
	private Boolean autoMapping;

	private ResultMap() {
	}

	public static class Builder {

		private static final Log log = LogFactory.getLog(Builder.class);

		private ResultMap resultMap = new ResultMap();

		public Builder(Configuration configuration, String id, Class<?> type,
			List<ResultMapping> resultMappings) {
			this(configuration, id, type, resultMappings, null);
		}

		public Builder(Configuration configuration, String id, Class<?> type,
			List<ResultMapping> resultMappings, Boolean autoMapping) {
			resultMap.configuration = configuration;
			resultMap.id = id;
			resultMap.type = type;
			resultMap.resultMappings = resultMappings;
			resultMap.autoMapping = autoMapping;
		}

		public Builder discriminator(Discriminator discriminator) {
			resultMap.discriminator = discriminator;
			return this;
		}

		public Class<?> type() {
			return resultMap.type;
		}

		/**
		 * 创建ResultMap对象
		 */
		public ResultMap build() {
			if (resultMap.id == null) {
				throw new IllegalArgumentException("ResultMaps must have an id");
			}
			//初始化一些必要集合
			resultMap.mappedColumns = new HashSet<>();
			resultMap.mappedProperties = new HashSet<>();
			resultMap.idResultMappings = new ArrayList<>();
			resultMap.constructorResultMappings = new ArrayList<>();
			resultMap.propertyResultMappings = new ArrayList<>();
			final List<String> constructorArgNames = new ArrayList<>();
			for (ResultMapping resultMapping : resultMap.resultMappings) {
				//筛选内嵌<resultMap/>和语句标签
				resultMap.hasNestedQueries =
					resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
				resultMap.hasNestedResultMaps =
					resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null
						&& resultMapping.getResultSet() == null);
				final String column = resultMapping.getColumn();
				if (column != null) {
					//筛选数据库表所有列名
					resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
				} else if (resultMapping.isCompositeResult()) {
					//组合列名分开塞入
					for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
						final String compositeColumn = compositeResultMapping.getColumn();
						if (compositeColumn != null) {
							resultMap.mappedColumns
								.add(compositeColumn.toUpperCase(Locale.ENGLISH));
						}
					}
				}
				final String property = resultMapping.getProperty();
				if (property != null) {
					resultMap.mappedProperties.add(property);
				}
				//处理<constructor/>相关标签
				if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
					resultMap.constructorResultMappings.add(resultMapping);
					if (resultMapping.getProperty() != null) {
						constructorArgNames.add(resultMapping.getProperty());
					}
				} else {
					//非<constructor/>普通标签，直接保存
					resultMap.propertyResultMappings.add(resultMapping);
				}
				//存在id属性的标签单独在保存一份
				if (resultMapping.getFlags().contains(ResultFlag.ID)) {
					resultMap.idResultMappings.add(resultMapping);
				}
			}
			//保证idResultMappings非空
			if (resultMap.idResultMappings.isEmpty()) {
				resultMap.idResultMappings.addAll(resultMap.resultMappings);
			}
			if (!constructorArgNames.isEmpty()) {
				//配置的<constructor/>参数名
				final List<String> actualArgNames = argNamesOfMatchingConstructor(
					constructorArgNames);
				//没有找到但是配置了，说明配置错了
				if (actualArgNames == null) {
					throw new BuilderException("Error in result map '" + resultMap.id
						+ "'. Failed to find a constructor in '"
						+ resultMap.getType().getName() + "' by arg names " + constructorArgNames
						+ ". There might be more info in debug log.");
				}
				//<constructor/>参数按照actualArgNames进行排序
				Collections.sort(resultMap.constructorResultMappings, (o1, o2) -> {
					int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
					int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
					return paramIdx1 - paramIdx2;
				});
			}
			// lock down collections
			//解析完变成不可变类型
			resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
			resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
			resultMap.constructorResultMappings = Collections
				.unmodifiableList(resultMap.constructorResultMappings);
			resultMap.propertyResultMappings = Collections
				.unmodifiableList(resultMap.propertyResultMappings);
			resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
			return resultMap;
		}

		/**
		 * 获取构造器真实参数名列表，主要是在验证所配构造器是否正确
		 */
		private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
			//得到结果集对应JavaType所有的构造器
			Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
			for (Constructor<?> constructor : constructors) {
				Class<?>[] paramTypes = constructor.getParameterTypes();
				//构造器参数数量相同是第一步
				if (constructorArgNames.size() == paramTypes.length) {
					//构造器参数名列表
					List<String> paramNames = getArgNames(constructor);
					//配置构造器和当前遍历构造器是否完全匹配
					if (constructorArgNames.containsAll(paramNames)
						&& argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
						return paramNames;
					}
				}
			}
			return null;
		}

		/**
		 * 构造器参数类型是否匹配
		 *
		 * @param constructorArgNames 待验证的构造器参数列表
		 * @param paramTypes 匹配构造器的参数类型数组
		 * @param paramNames 匹配构造器参数名集合
		 */
		private boolean argTypesMatch(final List<String> constructorArgNames,
			Class<?>[] paramTypes, List<String> paramNames) {
			for (int i = 0; i < constructorArgNames.size(); i++) {
				//参数下标对应的参数真实类型
				Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
				//参数下表对应的配置类型
				Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
				//两者必须相等，否则false
				if (!actualType.equals(specifiedType)) {
					if (log.isDebugEnabled()) {
						log.debug("While building result map '" + resultMap.id
							+ "', found a constructor with arg names " + constructorArgNames
							+ ", but the type of '" + constructorArgNames.get(i)
							+ "' did not match. Specified: [" + specifiedType.getName()
							+ "] Declared: ["
							+ actualType.getName() + "]");
					}
					return false;
				}
			}
			return true;
		}

		private List<String> getArgNames(Constructor<?> constructor) {
			List<String> paramNames = new ArrayList<>();
			List<String> actualParamNames = null;
			final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
			int paramCount = paramAnnotations.length;
			for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
				String name = null;
				for (Annotation annotation : paramAnnotations[paramIndex]) {
					if (annotation instanceof Param) {
						name = ((Param) annotation).value();
						break;
					}
				}
				if (name == null && resultMap.configuration.isUseActualParamName()) {
					if (actualParamNames == null) {
						actualParamNames = ParamNameUtil.getParamNames(constructor);
					}
					if (actualParamNames.size() > paramIndex) {
						name = actualParamNames.get(paramIndex);
					}
				}
				paramNames.add(name != null ? name : "arg" + paramIndex);
			}
			return paramNames;
		}
	}

	public String getId() {
		return id;
	}

	public boolean hasNestedResultMaps() {
		return hasNestedResultMaps;
	}

	public boolean hasNestedQueries() {
		return hasNestedQueries;
	}

	public Class<?> getType() {
		return type;
	}

	public List<ResultMapping> getResultMappings() {
		return resultMappings;
	}

	public List<ResultMapping> getConstructorResultMappings() {
		return constructorResultMappings;
	}

	public List<ResultMapping> getPropertyResultMappings() {
		return propertyResultMappings;
	}

	public List<ResultMapping> getIdResultMappings() {
		return idResultMappings;
	}

	public Set<String> getMappedColumns() {
		return mappedColumns;
	}

	public Set<String> getMappedProperties() {
		return mappedProperties;
	}

	public Discriminator getDiscriminator() {
		return discriminator;
	}

	public void forceNestedResultMaps() {
		hasNestedResultMaps = true;
	}

	public Boolean getAutoMapping() {
		return autoMapping;
	}

}

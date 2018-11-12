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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

	//xpath解析器
	private final XPathParser parser;
	//解析Mapper的辅助类
	private final MapperBuilderAssistant builderAssistant;
	//可重用<sql/>代码块  比如<sql id="userColumns"> ${alias}.id,${alias}.username,${alias}.password </sql>
	private final Map<String, XNode> sqlFragments;
	//资源地址
	private final String resource;

	@Deprecated
	public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
		Map<String, XNode> sqlFragments, String namespace) {
		this(reader, configuration, resource, sqlFragments);
		this.builderAssistant.setCurrentNamespace(namespace);
	}

	@Deprecated
	public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
		Map<String, XNode> sqlFragments) {
		this(new XPathParser(reader, true, configuration.getVariables(),
				new XMLMapperEntityResolver()),
			configuration, resource, sqlFragments);
	}

	public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
		Map<String, XNode> sqlFragments, String namespace) {
		this(inputStream, configuration, resource, sqlFragments);
		this.builderAssistant.setCurrentNamespace(namespace);
	}

	public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
		Map<String, XNode> sqlFragments) {
		this(new XPathParser(inputStream, true, configuration.getVariables(),
				new XMLMapperEntityResolver()),
			configuration, resource, sqlFragments);
	}

	private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource,
		Map<String, XNode> sqlFragments) {
		super(configuration);
		this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
		this.parser = parser;
		this.sqlFragments = sqlFragments;
		this.resource = resource;
	}

	/**
	 * 解析resource对应Mapper文件
	 */
	public void parse() {
		//Configuration中还没有记录该资源
		if (!configuration.isResourceLoaded(resource)) {
			//解析Mapper
			configurationElement(parser.evalNode("/mapper"));
			//标记该resource已被处理
			configuration.addLoadedResource(resource);
			//
			bindMapperForNamespace();
		}

		//解析异常/未完成的<resultMap/>
		parsePendingResultMaps();
		//解析异常/未完成<cacheRef/>
		parsePendingCacheRefs();
		//解析异常/未完成语句标签
		parsePendingStatements();
	}

	public XNode getSqlFragment(String refid) {
		return sqlFragments.get(refid);
	}

	/**
	 * 解析Mapper.xml中的一些配置信息
	 * 配置文件中的顶层元素如下：
	 * <p>
	 * cache – 给定命名空间的缓存配置。
	 * cache-ref – 其他命名空间缓存配置的引用。
	 * resultMap – 是最复杂也是最强大的元素，用来描述如何从数据库结果集中来加载对象。
	 * parameterMap – 已废弃！老式风格的参数映射。内联参数是首选,这个元素可能在将来被移除，这里不会记录。
	 * sql – 可被其他语句引用的可重用语句块。
	 * insert – 映射插入语句
	 * update – 映射更新语句
	 * delete – 映射删除语句
	 * select – 映射查询语句
	 *
	 * @param context Mapper.xml对应DOM
	 */
	private void configurationElement(XNode context) {
		try {
			//获取namespace
			String namespace = context.getStringAttribute("namespace");
			//namespace不可为null
			if (namespace == null || namespace.equals("")) {
				throw new BuilderException("Mapper's namespace cannot be empty");
			}
			//设置当前名称空间
			builderAssistant.setCurrentNamespace(namespace);
			//解析其他命名空间的缓存配置引用
			cacheRefElement(context.evalNode("cache-ref"));
			//解析缓存配置
			cacheElement(context.evalNode("cache"));
			parameterMapElement(context.evalNodes("/mapper/parameterMap"));
			/**
			 * <mapper>
			 *     <resultMap>
			 *         .....
			 *     </resultMap>
			 *     <resultMap>
			 *         .....
			 *     </resultMap>
			 *     ....
			 * </mapper>
			 */
			resultMapElements(context.evalNodes("/mapper/resultMap"));
			//解析<sql/>片段
			sqlElement(context.evalNodes("/mapper/sql"));
			//解析Mapper接口中方法对应的SQL语句块
			buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
		} catch (Exception e) {
			throw new BuilderException(
				"Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
		}
	}

	private void buildStatementFromContext(List<XNode> list) {
		//如果制定了数据库，要根据相应数据库分析方言
		if (configuration.getDatabaseId() != null) {
			buildStatementFromContext(list, configuration.getDatabaseId());
		}
		buildStatementFromContext(list, null);
	}

	/**
	 * 解析<select/>等语句标签
	 *
	 * @param list 语句标签集合
	 * @param requiredDatabaseId 数据库类型id
	 */
	private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
		for (XNode context : list) {
			//创建解析语句标签的对象
			final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration,
				builderAssistant, context, requiredDatabaseId);
			try {
				//解析
				statementParser.parseStatementNode();
			} catch (IncompleteElementException e) {
				//解析失败放入未解析完成列表
				configuration.addIncompleteStatement(statementParser);
			}
		}
	}

	/**
	 * 解析异常/未完成的<resultMap/>
	 */
	private void parsePendingResultMaps() {
		//得到有问题的<resultMap/>解析器
		Collection<ResultMapResolver> incompleteResultMaps = configuration
			.getIncompleteResultMaps();
		synchronized (incompleteResultMaps) {
			Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
			while (iter.hasNext()) {
				try {
					//尝试解析
					iter.next().resolve();
					//解析成功从异常列表中移除
					iter.remove();
				} catch (IncompleteElementException e) {
					// ResultMap is still missing a resource...
				}
			}
		}
	}

	/**
	 * 解析异常/未完成的<cacheRef/>
	 */
	private void parsePendingCacheRefs() {
		Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
		synchronized (incompleteCacheRefs) {
			Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
			while (iter.hasNext()) {
				try {
					iter.next().resolveCacheRef();
					iter.remove();
				} catch (IncompleteElementException e) {
					// Cache ref is still missing a resource...
				}
			}
		}
	}

	/**
	 * 解析异常/未完成的<cache/>
	 */
	private void parsePendingStatements() {
		Collection<XMLStatementBuilder> incompleteStatements = configuration
			.getIncompleteStatements();
		synchronized (incompleteStatements) {
			Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
			while (iter.hasNext()) {
				try {
					iter.next().parseStatementNode();
					iter.remove();
				} catch (IncompleteElementException e) {
					// Statement is still missing a resource...
				}
			}
		}
	}

	/**
	 * <cache-ref namespace="com.someone.application.data.SomeMapper"/>
	 * 该命名空间缓存引用其他命名空间引用
	 */
	private void cacheRefElement(XNode context) {
		if (context != null) {
			//将对于其他命名空间缓存的引用记录到Configuration中的cacheRefMap中
			//key为当前namespace，value为被引用的namespace
			configuration.addCacheRef(builderAssistant.getCurrentNamespace(),
				context.getStringAttribute("namespace"));
			//初始化缓存引用解析器
			CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant,
				context.getStringAttribute("namespace"));
			try {
				//解析缓存引用，实际上委托给MapperBuilderAssistant处理
				cacheRefResolver.resolveCacheRef();
			} catch (IncompleteElementException e) {
				//出现问题加入未完成缓存引用
				configuration.addIncompleteCacheRef(cacheRefResolver);
			}
		}
	}

	/**
	 * 举例：
	 * <p>
	 * 开启二级缓存并进行设置
	 * <cache
	 * eviction="FIFO"
	 * flushInterval="60000"
	 * size="512"
	 * readOnly="true"/>
	 * <p>
	 * 自定义缓存
	 * <cache type="com.domain.something.MyCustomCache">
	 * <property name="cacheFile" value="/tmp/my-custom-cache.tmp"/>
	 * </cache>
	 */
	private void cacheElement(XNode context) throws Exception {
		if (context != null) {
			//获取type属性，不存在默认PERPETUAL
			String type = context.getStringAttribute("type", "PERPETUAL");
			//获取type对应Class
			Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
			//回收策略，默认LRU
			String eviction = context.getStringAttribute("eviction", "LRU");
			//回收策略对应Class
			Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
			//刷新间隔
			Long flushInterval = context.getLongAttribute("flushInterval");
			//缓存个数
			Integer size = context.getIntAttribute("size");
			//是否可读
			boolean readWrite = !context.getBooleanAttribute("readOnly", false);
			//是否阻塞
			boolean blocking = context.getBooleanAttribute("blocking", false);
			//子标签
			Properties props = context.getChildrenAsProperties();
			//创建Cache实例存放在Configuration的caches中
			builderAssistant
				.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking,
					props);
		}
	}

	private void parameterMapElement(List<XNode> list) throws Exception {
		for (XNode parameterMapNode : list) {
			String id = parameterMapNode.getStringAttribute("id");
			String type = parameterMapNode.getStringAttribute("type");
			Class<?> parameterClass = resolveClass(type);
			List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
			List<ParameterMapping> parameterMappings = new ArrayList<>();
			for (XNode parameterNode : parameterNodes) {
				String property = parameterNode.getStringAttribute("property");
				String javaType = parameterNode.getStringAttribute("javaType");
				String jdbcType = parameterNode.getStringAttribute("jdbcType");
				String resultMap = parameterNode.getStringAttribute("resultMap");
				String mode = parameterNode.getStringAttribute("mode");
				String typeHandler = parameterNode.getStringAttribute("typeHandler");
				Integer numericScale = parameterNode.getIntAttribute("numericScale");
				ParameterMode modeEnum = resolveParameterMode(mode);
				Class<?> javaTypeClass = resolveClass(javaType);
				JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
				@SuppressWarnings("unchecked")
				Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(
					typeHandler);
				ParameterMapping parameterMapping = builderAssistant
					.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum,
						resultMap, modeEnum, typeHandlerClass, numericScale);
				parameterMappings.add(parameterMapping);
			}
			builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
		}
	}

	/**
	 * 解析一堆<resultMap></resultMap>
	 *
	 * @param list <reslutMap></reslutMap>节点对应的Node集合
	 */
	private void resultMapElements(List<XNode> list) throws Exception {
		//可能有很多<resultMap>所以是个集合
		for (XNode resultMapNode : list) {
			try {
				resultMapElement(resultMapNode);
			} catch (IncompleteElementException e) {
				// ignore, it will be retried
			}
		}
	}

	private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
		return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList());
	}

	/**
	 * 解析每一个<resultMap></resultMap>
	 * <p>
	 * 举简单的例子
	 * <resultMap id="userResultMap" type="User">
	 * <id property="id" column="user_id" />
	 * <result property="username" column="user_name"/>
	 * <result property="password" column="hashed_password"/>
	 * </resultMap>
	 */
	private ResultMap resultMapElement(XNode resultMapNode,
		List<ResultMapping> additionalResultMappings) throws Exception {
		ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
		//唯一标示，没有设置的话会生成一个
		String id = resultMapNode.getStringAttribute("id",
			resultMapNode.getValueBasedIdentifier());
		//要求通过type找到结果集映射的类，可以使昵称、类全路径等，只要能通过他找到对应的类就行
		//这里做了三层默认值  type  -->  ofType  -->  resultType  -->  javaType
		String type = resultMapNode.getStringAttribute("type",
			resultMapNode.getStringAttribute("ofType",
				resultMapNode.getStringAttribute("resultType",
					resultMapNode.getStringAttribute("javaType"))));
		//extends属性标识该结果集映射继承自extends写的那个resultMap
		String extend = resultMapNode.getStringAttribute("extends");
		//是否开启自动映射
		Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
		//解析映射类的Class
		Class<?> typeClass = resolveClass(type);
		Discriminator discriminator = null;
		//<resultMapping/>的每一个子标签映射成一个或多个RequestMapping对象
		List<ResultMapping> resultMappings = new ArrayList<>();
		//添加其他的映射
		resultMappings.addAll(additionalResultMappings);
		//遍历<resultMap>子标签
		List<XNode> resultChildren = resultMapNode.getChildren();
		for (XNode resultChild : resultChildren) {
			//创建映射对象可能需要用指定的构造器
			if ("constructor".equals(resultChild.getName())) {
				//处理<constructor>标签
				processConstructorElement(resultChild, typeClass, resultMappings);
			} else if ("discriminator".equals(resultChild.getName())) {
				//处理<discriminator/>
				discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
			} else {
				List<ResultFlag> flags = new ArrayList<>();
				if ("id".equals(resultChild.getName())) {
					flags.add(ResultFlag.ID);
				}
				// 创建RequestMapping，放入集合
				resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
			}
		}
		//创建ResultMap的解析器对象
		ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass,
			extend, discriminator, resultMappings, autoMapping);
		try {
			//解析出ResultMap对象
			return resultMapResolver.resolve();
		} catch (IncompleteElementException e) {
			configuration.addIncompleteResultMap(resultMapResolver);
			throw e;
		}
	}

	/**
	 * 解析<constructor></constructor>，举例：
	 * <constructor>
	 * <idArg column="id" javaType="int"/>
	 * <arg column="username" javaType="String"/>
	 * <arg column="age" javaType="_int"/>
	 * </constructor>
	 *
	 * @param resultChild <constructor/>标签元素
	 * @param resultType <resultMap/>对应映射类Class
	 * @param resultMappings 其实就是个空集合
	 */
	private void processConstructorElement(XNode resultChild, Class<?> resultType,
		List<ResultMapping> resultMappings) throws Exception {
		List<XNode> argChildren = resultChild.getChildren();
		for (XNode argChild : argChildren) {
			//属性类型标识集合
			List<ResultFlag> flags = new ArrayList<>();
			//必定要先放一个标识<constructor/>的标识
			flags.add(ResultFlag.CONSTRUCTOR);
			if ("idArg".equals(argChild.getName())) {
				//主键标识
				flags.add(ResultFlag.ID);
			}
			//每一个标签构建出一个ResultMapping放入集合
			resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
		}
	}

	/**
	 * 解析<discriminator></discriminator> 举例，可能存在两种类型：
	 * 第一种：
	 * <discriminator javaType="int" column="vehicle_type">
	 * <case value="1" resultMap="carResult"/>
	 * </discriminator>
	 * <resultMap id="carResult" type="Car">
	 * <result property=”doorCount” column="door_count" />
	 * </resultMap>
	 * 第二种：
	 * <discriminator javaType="int" column="vehicle_type">
	 * <case value="1" resultType="carResult">
	 * <result property=”doorCount” column="door_count" />
	 * </case>
	 * </discriminator>
	 *
	 * @param context <discriminator/>标签节点
	 * @param resultType <resultMap/>的type属性对应Class，映射结果
	 * @param resultMappings 用于保存最后解析出的<discriminator/>
	 */
	private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType,
		List<ResultMapping> resultMappings) throws Exception {
		//获取一些属性
		String column = context.getStringAttribute("column");
		String javaType = context.getStringAttribute("javaType");
		String jdbcType = context.getStringAttribute("jdbcType");
		String typeHandler = context.getStringAttribute("typeHandler");
		//解析数据库中列的Java类型
		Class<?> javaTypeClass = resolveClass(javaType);
		@SuppressWarnings("unchecked")
		//解析类型处理器
			Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(
			typeHandler);
		//JdbcType
		JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
		//存在多个case，需要一个Map
		Map<String, String> discriminatorMap = new HashMap<>();
		for (XNode caseChild : context.getChildren()) {
			String value = caseChild.getStringAttribute("value");
			//value真正映射又是一个ResultMap，做内嵌处理
			String resultMap = caseChild.getStringAttribute("resultMap",
				processNestedResultMappings(caseChild, resultMappings));
			//存入一种映射关系
			discriminatorMap.put(value, resultMap);
		}
		//构建出Discriminator
		return builderAssistant
			.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass,
				discriminatorMap);
	}

	/**
	 * 解析所有的sql片段，举例：
	 * <sql id="sometable">
	 * ${prefix}Table
	 * </sql>
	 *
	 * <sql id="someinclude">
	 * from
	 * <include refid="${include_target}"/>
	 * </sql>
	 *
	 * @param list 所有的sql片段节点
	 */
	private void sqlElement(List<XNode> list) throws Exception {
		//如果存在数据库类型的标识，取出来，在处理时需要
		if (configuration.getDatabaseId() != null) {
			sqlElement(list, configuration.getDatabaseId());
		}
		sqlElement(list, null);
	}

	private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
		//遍历每一个sql片段
		for (XNode context : list) {
			//每个sql片段也可指定数据库类型
			String databaseId = context.getStringAttribute("databaseId");
			//sql片段唯一标识
			String id = context.getStringAttribute("id");
			//id需要加上当前名称空间和引用名称空间前缀
			id = builderAssistant.applyCurrentNamespace(id, false);
			if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
				sqlFragments.put(id, context);
			}
		}
	}

	/**
	 * <sql/>中配置的databaseId和整个Mapper的requiredDatabaseId必须一致，怎么
	 * 可能整个Mybatis用一个数据库，Mapper里面的某个sql片段用另一套呢
	 *
	 * @param id sql片段唯一标识
	 * @param databaseId sql片段要求数据库id
	 * @param requiredDatabaseId 整体要求数据库id
	 */
	private boolean databaseIdMatchesCurrent(String id, String databaseId,
		String requiredDatabaseId) {
		if (requiredDatabaseId != null) {
			//两者必须相等
			if (!requiredDatabaseId.equals(databaseId)) {
				return false;
			}
		} else {
			//一个一个没有也不行
			if (databaseId != null) {
				return false;
			}
			// skip this fragment if there is a previous one with a not null databaseId
			//如果已经存在了也要再检查一下
			if (this.sqlFragments.containsKey(id)) {
				XNode context = this.sqlFragments.get(id);
				if (context.getStringAttribute("databaseId") != null) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 根据每一个标签（非父标签）构建ResultMapping
	 *
	 * @param context 子标签
	 * @param resultType <resultMap/>的type属性对应的映射Java类
	 * @param flags 用于标识哪种标签
	 */
	private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType,
		List<ResultFlag> flags) throws Exception {
		String property;
		if (flags.contains(ResultFlag.CONSTRUCTOR)) {
			property = context.getStringAttribute("name");
		} else {
			property = context.getStringAttribute("property");
		}
		String column = context.getStringAttribute("column");
		String javaType = context.getStringAttribute("javaType");
		String jdbcType = context.getStringAttribute("jdbcType");
		String nestedSelect = context.getStringAttribute("select");
		String nestedResultMap = context.getStringAttribute("resultMap",
			processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
		String notNullColumn = context.getStringAttribute("notNullColumn");
		String columnPrefix = context.getStringAttribute("columnPrefix");
		String typeHandler = context.getStringAttribute("typeHandler");
		String resultSet = context.getStringAttribute("resultSet");
		String foreignColumn = context.getStringAttribute("foreignColumn");
		boolean lazy = "lazy".equals(context.getStringAttribute("fetchType",
			configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
		Class<?> javaTypeClass = resolveClass(javaType);
		@SuppressWarnings("unchecked")
		Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(
			typeHandler);
		JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
		return builderAssistant
			.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum,
				nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags,
				resultSet, foreignColumn, lazy);
	}

	/**
	 * 处理内嵌的resultMap
	 */
	private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings)
		throws Exception {
		//判断resultMap属性所处的标签
		if ("association".equals(context.getName())
			|| "collection".equals(context.getName())
			|| "case".equals(context.getName())) {
			//为什么没有select属性才需要处理，不清楚？？？
			if (context.getStringAttribute("select") == null) {
				//再次调用resultMapElement递归处理
				ResultMap resultMap = resultMapElement(context, resultMappings);
				return resultMap.getId();
			}
		}
		return null;
	}

	private void bindMapperForNamespace() {
		String namespace = builderAssistant.getCurrentNamespace();
		if (namespace != null) {
			Class<?> boundType = null;
			try {
				//每一个namespace对应一个Mapper.java，得到Class
				boundType = Resources.classForName(namespace);
			} catch (ClassNotFoundException e) {
				//ignore, bound type is not required
			}
			if (boundType != null) {
				//Mapper还没注册
				if (!configuration.hasMapper(boundType)) {
					// Spring may not know the real resource name so we set a flag
					// to prevent loading again this resource from the mapper interface
					// look at MapperAnnotationBuilder#loadXmlResource
					configuration.addLoadedResource("namespace:" + namespace);
					//添加Mapper到Configuration.mapperRegistry中
					configuration.addMapper(boundType);
				}
			}
		}
	}

}

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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

	//Mapper.xml的名称空间，实际上是Mapper对应的Mapper接口全路径名
	private String currentNamespace;
	//Mapper.xml的文件路径
	private final String resource;
	//Mapper中所有缓存
	private Cache currentCache;
	//是否成功解析<cacheRef/>
	private boolean unresolvedCacheRef; // issue #676

	public MapperBuilderAssistant(Configuration configuration, String resource) {
		super(configuration);
		ErrorContext.instance().resource(resource);
		this.resource = resource;
	}

	public String getCurrentNamespace() {
		return currentNamespace;
	}

	/**
	 * 设置当前Mapper.xml的名称空间，实际上就是设置Mapper接口的全路径
	 */
	public void setCurrentNamespace(String currentNamespace) {
		if (currentNamespace == null) {
			throw new BuilderException(
				"The mapper element requires a namespace attribute to be specified.");
		}
		//保证设置的和当前存在的一致
		if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
			throw new BuilderException("Wrong namespace. Expected '"
				+ this.currentNamespace + "' but found '" + currentNamespace + "'.");
		}

		this.currentNamespace = currentNamespace;
	}

	/**
	 * 真实命名空间 currentNamespace.base，
	 * 比如有个标签 <association property="author" column="author_id" javaType="Author" select="selectAuthor"/>
	 * 这里的select实际上对应着某个语句标签<select id= "selectAuthor"/>，那么这个被引用的<select/>标签的命名空间就是
	 * currentNamespace.base
	 */
	public String applyCurrentNamespace(String base, boolean isReference) {
		if (base == null) {
			return null;
		}
		if (isReference) {
			// is it qualified with any namespace yet?
			if (base.contains(".")) {
				return base;
			}
		} else {
			// is it qualified with this namespace yet?
			if (base.startsWith(currentNamespace + ".")) {
				return base;
			}
			if (base.contains(".")) {
				throw new BuilderException(
					"Dots are not allowed in element names, please remove it from " + base);
			}
		}
		return currentNamespace + "." + base;
	}

	/**
	 * 使用namespace对应的缓存配置，实际上就是获取namespace对应的Cache
	 *
	 * @param namespace 被引用缓存的namespace
	 */
	public Cache useCacheRef(String namespace) {
		if (namespace == null) {
			throw new BuilderException("cache-ref element requires a namespace attribute.");
		}
		try {
			unresolvedCacheRef = true;
			Cache cache = configuration.getCache(namespace);
			//既然被引用，缓存就必须存在
			if (cache == null) {
				throw new IncompleteElementException(
					"No cache for namespace '" + namespace + "' could be found.");
			}
			currentCache = cache;
			unresolvedCacheRef = false;
			return cache;
		} catch (IllegalArgumentException e) {
			throw new IncompleteElementException(
				"No cache for namespace '" + namespace + "' could be found.", e);
		}
	}

	/**
	 * 构建缓存对象
	 *
	 * @param typeClass 缓存类
	 * @param evictionClass 回收策略类
	 * @param flushInterval 刷新间隔
	 * @param size 缓存个数
	 * @param readWrite 可读写
	 * @param blocking 阻塞
	 * @param props 其他配置
	 * @return Cache缓存对象
	 */
	public Cache useNewCache(Class<? extends Cache> typeClass,
		Class<? extends Cache> evictionClass,
		Long flushInterval,
		Integer size,
		boolean readWrite,
		boolean blocking,
		Properties props) {
		Cache cache = new CacheBuilder(currentNamespace)
			.implementation(valueOrDefault(typeClass, PerpetualCache.class))
			//初始化时，将回收策略对应的Cache作为初始的包装类，如果没有evict属性，默认LruCache
			.addDecorator(valueOrDefault(evictionClass, LruCache.class))
			.clearInterval(flushInterval)
			.size(size)
			.readWrite(readWrite)
			.blocking(blocking)
			.properties(props)
			.build();
		configuration.addCache(cache);
		currentCache = cache;
		return cache;
	}

	public ParameterMap addParameterMap(String id, Class<?> parameterClass,
		List<ParameterMapping> parameterMappings) {
		id = applyCurrentNamespace(id, false);
		ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass,
			parameterMappings).build();
		configuration.addParameterMap(parameterMap);
		return parameterMap;
	}

	public ParameterMapping buildParameterMapping(
		Class<?> parameterType,
		String property,
		Class<?> javaType,
		JdbcType jdbcType,
		String resultMap,
		ParameterMode parameterMode,
		Class<? extends TypeHandler<?>> typeHandler,
		Integer numericScale) {
		resultMap = applyCurrentNamespace(resultMap, true);

		// Class parameterType = parameterMapBuilder.type();
		Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType,
			jdbcType);
		TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

		return new ParameterMapping.Builder(configuration, property, javaTypeClass)
			.jdbcType(jdbcType)
			.resultMapId(resultMap)
			.mode(parameterMode)
			.numericScale(numericScale)
			.typeHandler(typeHandlerInstance)
			.build();
	}

	/**
	 * 将所有ResultMapping-->ResultMap的过程
	 *
	 * @param id <resultMap/>唯一标识
	 * @param type <resultMap/>映射成的Class
	 * @param extend 继承的<resultMap/>id
	 * @param discriminator 决定映射成为那个类的鉴别器
	 * @param resultMappings 包含所有<resultMap/>属性的集合
	 * @param autoMapping 是否自动映射
	 * @return ResultMap对象
	 */
	public ResultMap addResultMap(
		String id,
		Class<?> type,
		String extend,
		Discriminator discriminator,
		List<ResultMapping> resultMappings,
		Boolean autoMapping) {
		//获取<resultMap/>完整id，currentNamespace.id
		id = applyCurrentNamespace(id, false);
		//获得<resultMap/>继承结果集映射的完整id
		extend = applyCurrentNamespace(extend, true);

		//存在继承关系
		if (extend != null) {
			//查不到继承的父<resultMap/>报错
			if (!configuration.hasResultMap(extend)) {
				throw new IncompleteElementException(
					"Could not find a parent resultmap with id '" + extend + "'");
			}
			ResultMap resultMap = configuration.getResultMap(extend);
			//取出父类中所有RequestMapping项
			List<ResultMapping> extendedResultMappings = new ArrayList<>(
				resultMap.getResultMappings());
			//移除一样的ResultMapping
			extendedResultMappings.removeAll(resultMappings);
			// Remove parent constructor if this resultMap declares a constructor.
			boolean declaresConstructor = false;
			//判断当前<resultMap/>中是否存在指定的<constructor/>
			for (ResultMapping resultMapping : resultMappings) {
				if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
					declaresConstructor = true;
					break;
				}
			}
			if (declaresConstructor) {
				//如果父<resultMap/>中也声明了<constructor/>，将该条目对应的ResultMapping移除
				Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings
					.iterator();
				while (extendedResultMappingsIter.hasNext()) {
					if (extendedResultMappingsIter.next().getFlags()
						.contains(ResultFlag.CONSTRUCTOR)) {
						extendedResultMappingsIter.remove();
					}
				}
			}
			//此时extendedResultMapping和resultMappings都是是唯一的，合在一起
			resultMappings.addAll(extendedResultMappings);
		}
		//封装成ResultMap
		ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings,
			autoMapping)
			.discriminator(discriminator)
			.build();
		//将ResultMap放入Configuration
		configuration.addResultMap(resultMap);
		return resultMap;
	}

	/**
	 * 构建鉴别器对象
	 *
	 * @param resultType Mapper.xml对应Mapper.java类型
	 * @param column 数据库列名
	 * @param javaType <resultMap/>映射Java类型
	 * @param jdbcType jdbcType
	 * @param typeHandler 类型转换处理器
	 * @param discriminatorMap value - resultMap-id对
	 */
	public Discriminator buildDiscriminator(
		Class<?> resultType,
		String column,
		Class<?> javaType,
		JdbcType jdbcType,
		Class<? extends TypeHandler<?>> typeHandler,
		Map<String, String> discriminatorMap) {
		//将resultMap属性对应的ResultMapping对象生成
		ResultMapping resultMapping = buildResultMapping(
			resultType,
			null,
			column,
			javaType,
			jdbcType,
			null,
			null,
			null,
			null,
			typeHandler,
			new ArrayList<ResultFlag>(),
			null,
			null,
			false);
		Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
		//遍历鉴别器中多个case，每个key转换成currentNamespace.resultMapId，value为resultMapId
		for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
			String resultMap = e.getValue();
			resultMap = applyCurrentNamespace(resultMap, true);
			namespaceDiscriminatorMap.put(e.getKey(), resultMap);
		}
		return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap)
			.build();
	}

	public MappedStatement addMappedStatement(
		String id,
		SqlSource sqlSource,
		StatementType statementType,
		SqlCommandType sqlCommandType,
		Integer fetchSize,
		Integer timeout,
		String parameterMap,
		Class<?> parameterType,
		String resultMap,
		Class<?> resultType,
		ResultSetType resultSetType,
		boolean flushCache,
		boolean useCache,
		boolean resultOrdered,
		KeyGenerator keyGenerator,
		String keyProperty,
		String keyColumn,
		String databaseId,
		LanguageDriver lang,
		String resultSets) {

		if (unresolvedCacheRef) {
			throw new IncompleteElementException("Cache-ref not yet resolved");
		}

		id = applyCurrentNamespace(id, false);
		boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

		MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id,
			sqlSource, sqlCommandType)
			.resource(resource)
			.fetchSize(fetchSize)
			.timeout(timeout)
			.statementType(statementType)
			.keyGenerator(keyGenerator)
			.keyProperty(keyProperty)
			.keyColumn(keyColumn)
			.databaseId(databaseId)
			.lang(lang)
			.resultOrdered(resultOrdered)
			.resultSets(resultSets)
			.resultMaps(getStatementResultMaps(resultMap, resultType, id))
			.resultSetType(resultSetType)
			.flushCacheRequired(valueOrDefault(flushCache, !isSelect))
			.useCache(valueOrDefault(useCache, isSelect))
			.cache(currentCache);

		ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType,
			id);
		if (statementParameterMap != null) {
			statementBuilder.parameterMap(statementParameterMap);
		}

		MappedStatement statement = statementBuilder.build();
		configuration.addMappedStatement(statement);
		return statement;
	}

	private <T> T valueOrDefault(T value, T defaultValue) {
		return value == null ? defaultValue : value;
	}

	private ParameterMap getStatementParameterMap(
		String parameterMapName,
		Class<?> parameterTypeClass,
		String statementId) {
		parameterMapName = applyCurrentNamespace(parameterMapName, true);
		ParameterMap parameterMap = null;
		if (parameterMapName != null) {
			try {
				parameterMap = configuration.getParameterMap(parameterMapName);
			} catch (IllegalArgumentException e) {
				throw new IncompleteElementException(
					"Could not find parameter map " + parameterMapName, e);
			}
		} else if (parameterTypeClass != null) {
			List<ParameterMapping> parameterMappings = new ArrayList<>();
			parameterMap = new ParameterMap.Builder(
				configuration,
				statementId + "-Inline",
				parameterTypeClass,
				parameterMappings).build();
		}
		return parameterMap;
	}

	private List<ResultMap> getStatementResultMaps(
		String resultMap,
		Class<?> resultType,
		String statementId) {
		resultMap = applyCurrentNamespace(resultMap, true);

		List<ResultMap> resultMaps = new ArrayList<>();
		if (resultMap != null) {
			String[] resultMapNames = resultMap.split(",");
			for (String resultMapName : resultMapNames) {
				try {
					resultMaps.add(configuration.getResultMap(resultMapName.trim()));
				} catch (IllegalArgumentException e) {
					throw new IncompleteElementException(
						"Could not find result map " + resultMapName, e);
				}
			}
		} else if (resultType != null) {
			ResultMap inlineResultMap = new ResultMap.Builder(
				configuration,
				statementId + "-Inline",
				resultType,
				new ArrayList<ResultMapping>(),
				null).build();
			resultMaps.add(inlineResultMap);
		}
		return resultMaps;
	}

	/**
	 * 创建ResultMapping对象
	 */
	public ResultMapping buildResultMapping(
		Class<?> resultType,
		String property,
		String column,
		Class<?> javaType,
		JdbcType jdbcType,
		String nestedSelect,
		String nestedResultMap,
		String notNullColumn,
		String columnPrefix,
		Class<? extends TypeHandler<?>> typeHandler,
		List<ResultFlag> flags,
		String resultSet,
		String foreignColumn,
		boolean lazy) {
		//获得<resultMap/>要映射成为的对象Class
		Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
		//创建要映射对象的类型转换器
		TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
		//组合键，比如 column= ” {prop1=col1,prop2=col2} ”
		List<ResultMapping> composites = parseCompositeColumnName(column);
		return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
			.jdbcType(jdbcType)
			.nestedQueryId(applyCurrentNamespace(nestedSelect, true))
			.nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
			.resultSet(resultSet)
			.typeHandler(typeHandlerInstance)
			.flags(flags == null ? new ArrayList<ResultFlag>() : flags)
			.composites(composites)
			.notNullColumns(parseMultipleColumnNames(notNullColumn))
			.columnPrefix(columnPrefix)
			.foreignColumn(foreignColumn)
			.lazy(lazy)
			.build();
	}

	/**
	 * 和属性notNullColumn有关：
	 * 默认情况下，子对象仅在至少一个列映射到其属性非空时才创建。
	 * 通过对这个属性指定非空的列将改变默认行为，这样做之后Mybatis将仅在这些列非空时才创建一个子对象。
	 * 可以指定多个列名，使用逗号分隔。
	 */
	private Set<String> parseMultipleColumnNames(String columnName) {
		Set<String> columns = new HashSet<>();
		if (columnName != null) {
			if (columnName.indexOf(',') > -1) {
				StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
				while (parser.hasMoreTokens()) {
					String column = parser.nextToken();
					columns.add(column);
				}
			} else {
				columns.add(columnName);
			}
		}
		return columns;
	}

	/**
	 * 处理诸如复合主键等多列作为column的情况  column= ” {prop1=col1,prop2=col2} ”
	 *
	 * @param columnName column属性值
	 */
	private List<ResultMapping> parseCompositeColumnName(String columnName) {
		List<ResultMapping> composites = new ArrayList<>();
		if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
			StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
			while (parser.hasMoreTokens()) {
				String property = parser.nextToken();
				String column = parser.nextToken();
				ResultMapping complexResultMapping = new ResultMapping.Builder(
					configuration, property, column,
					configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
				composites.add(complexResultMapping);
			}
		}
		return composites;
	}

	private Class<?> resolveResultJavaType(Class<?> resultType, String property,
		Class<?> javaType) {
		if (javaType == null && property != null) {
			try {
				MetaClass metaResultType = MetaClass
					.forClass(resultType, configuration.getReflectorFactory());
				javaType = metaResultType.getSetterType(property);
			} catch (Exception e) {
				//ignore, following null check statement will deal with the situation
			}
		}
		if (javaType == null) {
			javaType = Object.class;
		}
		return javaType;
	}

	private Class<?> resolveParameterJavaType(Class<?> resultType, String property,
		Class<?> javaType, JdbcType jdbcType) {
		if (javaType == null) {
			if (JdbcType.CURSOR.equals(jdbcType)) {
				javaType = java.sql.ResultSet.class;
			} else if (Map.class.isAssignableFrom(resultType)) {
				javaType = Object.class;
			} else {
				MetaClass metaResultType = MetaClass
					.forClass(resultType, configuration.getReflectorFactory());
				javaType = metaResultType.getGetterType(property);
			}
		}
		if (javaType == null) {
			javaType = Object.class;
		}
		return javaType;
	}

	/**
	 * Backward compatibility signature
	 */
	public ResultMapping buildResultMapping(
		Class<?> resultType,
		String property,
		String column,
		Class<?> javaType,
		JdbcType jdbcType,
		String nestedSelect,
		String nestedResultMap,
		String notNullColumn,
		String columnPrefix,
		Class<? extends TypeHandler<?>> typeHandler,
		List<ResultFlag> flags) {
		return buildResultMapping(
			resultType, property, column, javaType, jdbcType, nestedSelect,
			nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null,
			configuration.isLazyLoadingEnabled());
	}

	public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
		if (langClass != null) {
			configuration.getLanguageRegistry().register(langClass);
		} else {
			langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
		}
		return configuration.getLanguageRegistry().getDriver(langClass);
	}

	/**
	 * Backward compatibility signature
	 */
	public MappedStatement addMappedStatement(
		String id,
		SqlSource sqlSource,
		StatementType statementType,
		SqlCommandType sqlCommandType,
		Integer fetchSize,
		Integer timeout,
		String parameterMap,
		Class<?> parameterType,
		String resultMap,
		Class<?> resultType,
		ResultSetType resultSetType,
		boolean flushCache,
		boolean useCache,
		boolean resultOrdered,
		KeyGenerator keyGenerator,
		String keyProperty,
		String keyColumn,
		String databaseId,
		LanguageDriver lang) {
		return addMappedStatement(
			id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
			parameterMap, parameterType, resultMap, resultType, resultSetType,
			flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
			keyColumn, databaseId, lang, null);
	}

}

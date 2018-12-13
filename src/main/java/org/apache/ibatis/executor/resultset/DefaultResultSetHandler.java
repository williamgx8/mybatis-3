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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

	private static final Object DEFERED = new Object();

	private final Executor executor;
	private final Configuration configuration;
	private final MappedStatement mappedStatement;
	private final RowBounds rowBounds;
	private final ParameterHandler parameterHandler;
	private final ResultHandler<?> resultHandler;
	private final BoundSql boundSql;
	private final TypeHandlerRegistry typeHandlerRegistry;
	private final ObjectFactory objectFactory;
	private final ReflectorFactory reflectorFactory;

	// nested resultmaps
	private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
	private final Map<String, Object> ancestorObjects = new HashMap<>();
	private Object previousRowValue;

	// multiple resultsets
	private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
	private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

	// Cached Automappings
	private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

	// temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
	private boolean useConstructorMappings;

	private static class PendingRelation {

		public MetaObject metaObject;
		public ResultMapping propertyMapping;
	}

	private static class UnMappedColumnAutoMapping {

		private final String column;
		private final String property;
		private final TypeHandler<?> typeHandler;
		private final boolean primitive;

		public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler,
			boolean primitive) {
			this.column = column;
			this.property = property;
			this.typeHandler = typeHandler;
			this.primitive = primitive;
		}
	}

	public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement,
		ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
		RowBounds rowBounds) {
		this.executor = executor;
		this.configuration = mappedStatement.getConfiguration();
		this.mappedStatement = mappedStatement;
		this.rowBounds = rowBounds;
		this.parameterHandler = parameterHandler;
		this.boundSql = boundSql;
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.objectFactory = configuration.getObjectFactory();
		this.reflectorFactory = configuration.getReflectorFactory();
		this.resultHandler = resultHandler;
	}

	//
	// HANDLE OUTPUT PARAMETER
	//

	@Override
	public void handleOutputParameters(CallableStatement cs) throws SQLException {
		final Object parameterObject = parameterHandler.getParameterObject();
		final MetaObject metaParam = configuration.newMetaObject(parameterObject);
		final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		for (int i = 0; i < parameterMappings.size(); i++) {
			final ParameterMapping parameterMapping = parameterMappings.get(i);
			if (parameterMapping.getMode() == ParameterMode.OUT
				|| parameterMapping.getMode() == ParameterMode.INOUT) {
				if (ResultSet.class.equals(parameterMapping.getJavaType())) {
					handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1),
						parameterMapping, metaParam);
				} else {
					final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
					metaParam
						.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
				}
			}
		}
	}

	private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping,
		MetaObject metaParam) throws SQLException {
		if (rs == null) {
			return;
		}
		try {
			final String resultMapId = parameterMapping.getResultMapId();
			final ResultMap resultMap = configuration.getResultMap(resultMapId);
			final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
			if (this.resultHandler == null) {
				final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
				handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
				metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
			} else {
				handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
			}
		} finally {
			// issue #228 (close resultsets)
			closeResultSet(rs);
		}
	}

	/**
	 * 处理ResultSet
	 *
	 * @param stmt 与不同数据相关的Statement
	 */
	//
	// HANDLE RESULT SETS
	//
	@Override
	public List<Object> handleResultSets(Statement stmt) throws SQLException {
		ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

		//非存储过程处理每一个Object实际上是一个List<resultMap对应对象>，resultMap对象有多少个就和
		//查询出来的rows行数对应
		final List<Object> multipleResults = new ArrayList<>();

		int resultSetCount = 0;
		//获取第一个ResultSet，并封装成ResultSetWrapper
		ResultSetWrapper rsw = getFirstResultSet(stmt);

		//普通查询只会存在一个ResultMap，只有存储过程相关才会有多个，存储过程不考虑
		List<ResultMap> resultMaps = mappedStatement.getResultMaps();
		//ResultMap数量，默认1
		int resultMapCount = resultMaps.size();
		//判断resultMap和resultSet，如果两者有一个存在，那么另外一个也必须有
		validateResultMapsCount(rsw, resultMapCount);
		//存在结果，并且映射数量多于结果数量
		//非存储过程resultMapCount = 1，所以while只会执行一次
		while (rsw != null && resultMapCount > resultSetCount) {
			//获取resultMap，第一次resultSetCount为0
			ResultMap resultMap = resultMaps.get(resultSetCount);
			//处理ResultSet，放入multipleResults
			handleResultSet(rsw, resultMap, multipleResults, null);
			//下一个结果集包装对象
			rsw = getNextResultSet(stmt);
			//清除嵌套resultMap
			cleanUpAfterHandlingResultSet();
			//结果集数量加一
			resultSetCount++;
		}

		//存储过程相关不作考虑
		String[] resultSets = mappedStatement.getResultSets();
		if (resultSets != null) {
			while (rsw != null && resultSetCount < resultSets.length) {
				ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
				if (parentMapping != null) {
					String nestedResultMapId = parentMapping.getNestedResultMapId();
					ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
					handleResultSet(rsw, resultMap, null, parentMapping);
				}
				rsw = getNextResultSet(stmt);
				cleanUpAfterHandlingResultSet();
				resultSetCount++;
			}
		}

		//如果是单个结果，取出单个结果返回，否则整个返回
		return collapseSingleResultList(multipleResults);
	}

	@Override
	public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
		ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

		ResultSetWrapper rsw = getFirstResultSet(stmt);

		List<ResultMap> resultMaps = mappedStatement.getResultMaps();

		int resultMapCount = resultMaps.size();
		validateResultMapsCount(rsw, resultMapCount);
		if (resultMapCount != 1) {
			throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
		}

		ResultMap resultMap = resultMaps.get(0);
		return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
	}

	/**
	 * 获取Statement中第一个ResultSet对象，并包装成ResultSetWrapper
	 */
	private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
		ResultSet rs = stmt.getResultSet();
		while (rs == null) {
			// move forward to get the first resultset in case the driver
			// doesn't return the resultset as the first result (HSQLDB 2.1)
			if (stmt.getMoreResults()) {
				rs = stmt.getResultSet();
			} else {
				if (stmt.getUpdateCount() == -1) {
					// no more results. Must be no resultset
					break;
				}
			}
		}
		return rs != null ? new ResultSetWrapper(rs, configuration) : null;
	}

	private ResultSetWrapper getNextResultSet(Statement stmt) {
		// Making this method tolerant of bad JDBC drivers
		try {
			if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
				// Crazy Standard JDBC way of determining if there are more results
				if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
					ResultSet rs = stmt.getResultSet();
					if (rs == null) {
						return getNextResultSet(stmt);
					} else {
						return new ResultSetWrapper(rs, configuration);
					}
				}
			}
		} catch (Exception e) {
			// Intentionally ignored.
		}
		return null;
	}

	private void closeResultSet(ResultSet rs) {
		try {
			if (rs != null) {
				rs.close();
			}
		} catch (SQLException e) {
			// ignore
		}
	}

	private void cleanUpAfterHandlingResultSet() {
		nestedResultObjects.clear();
	}

	/**
	 * 检测结果集存在时，resultMap也必须存在
	 */
	private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
		if (rsw != null && resultMapCount < 1) {
			throw new ExecutorException(
				"A query was run and no Result Maps were found for the Mapped Statement '"
					+ mappedStatement.getId()
					+ "'.  It's likely that neither a Result Type nor a Result Map was specified.");
		}
	}

	private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap,
		List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
		try {
			//处理存储过程忽略
			if (parentMapping != null) {
				handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
			} else {
				if (resultHandler == null) {
					//创建默认的结果处理器
					DefaultResultHandler defaultResultHandler = new DefaultResultHandler(
						objectFactory);
					//解析每行数据，生成resultMap对应对象/集合，并放入resultHandler的list中
					handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
					//取出解析出的值放入multipleResults
					multipleResults.add(defaultResultHandler.getResultList());
				} else {
					//此时resultHandler存在，可能是自定义的ResultHandler
					//自定义的ResultHandler并不会将解析出的结果放入multipleResults中
					handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
				}
			}
		} finally {
			// issue #228 (close resultsets)
			//关闭resultSet
			closeResultSet(rsw.getResultSet());
		}
	}

	/**
	 * 非存储过程操作multipleResults只会存在一个结果
	 */
	@SuppressWarnings("unchecked")
	private List<Object> collapseSingleResultList(List<Object> multipleResults) {
		//只存在一个结果取出该结果返回，否则返回整个multipleResults
		return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0)
			: multipleResults;
	}

	//
	// HANDLE ROWS FOR SIMPLE RESULTMAP
	//

	public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap,
		ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
		throws SQLException {
		//处理嵌套resultMap
		if (resultMap.hasNestedResultMaps()) {
			//确保不使用分页
			ensureNoRowBounds();
			//自定义ResultHandler不支持分页
			checkResultHandler();
			handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds,
				parentMapping);
		} else {
			//处理简单resultset和resultmap一一对应关系
			handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds,
				parentMapping);
		}
	}

	private void ensureNoRowBounds() {
		//safeRowBoundsEnabled 开启了允许在嵌套语句中使用分页，且确实存在分页相关数据报错
		//为什么允许分页还要报错？
		if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (
			rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT
				|| rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
			throw new ExecutorException(
				"Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
					+ "Use safeRowBoundsEnabled=false setting to bypass this check.");
		}
	}

	/**
	 * 自定义resultHandler不支持分页
	 */
	protected void checkResultHandler() {
		//resultOrdered属性：这个设置仅针对嵌套结果 select 语句适用：如果为 true，就是假设包含了嵌套结果集或是分组了，
		// 这样的话当返回一个主结果行的时候，就不会发生有对前面结果集的引用的情况。这就使得在获取嵌套的结果集的时候不至于导致内存不够用
		if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement
			.isResultOrdered()) {
			throw new ExecutorException(
				"Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
					+ "Use safeResultHandlerEnabled=false setting to bypass this check "
					+ "or ensure your statement returns ordered data and set resultOrdered=true on it.");
		}
	}

	/**
	 * 处理<resultMap/>没有嵌套的简单行值-结果集，mybatis自身实现的是逻辑分页，即通过代码逻辑的判断实现分页，
	 * 每次都查出一定数量的结果(具体数量由自定义或者不同数据库厂商查询的fetch size决定)，用代码跳转到开始的记录，
	 * 往下读取limit条
	 *
	 * @param rsw 结果集封装对象
	 * @param resultMap resultMap
	 * @param resultHandler 结果处理器
	 * @param rowBounds 分页跳转参数对象
	 * @param parentMapping 父resultMap？
	 */
	private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
		ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
		throws SQLException {
		DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
		ResultSet resultSet = rsw.getResultSet();
		//跳转到某行
		skipRows(resultSet, rowBounds);
		//没有到达本次分页的最后一条，session没有关闭，resultSet下一条依然有值，比如有很多行，每行依次解析
		while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet
			.next()) {
			//解析<discriminator/>对应的值，如果没有配置该标签返回的还是参数resultMap
			ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap,
				null);
			//获取每一行对应的对象
			Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
			//保存一行对象到resultHandler的list中
			storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
		}
	}

	/**
	 * 保存resultset每一行解析出的对象
	 *
	 * @param resultHandler 结果处理器
	 * @param resultContext 结果上下文
	 * @param rowValue 每一行对应对象
	 * @param parentMapping 非存储过程为null
	 */
	private void storeObject(ResultHandler<?> resultHandler,
		DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping,
		ResultSet rs) throws SQLException {
		if (parentMapping != null) {
			linkToParents(rs, parentMapping, rowValue);
		} else {
			callResultHandler(resultHandler, resultContext, rowValue);
		}
	}

	@SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
	private void callResultHandler(ResultHandler<?> resultHandler,
		DefaultResultContext<Object> resultContext, Object rowValue) {
		resultContext.nextResultObject(rowValue);
		((ResultHandler<Object>) resultHandler).handleResult(resultContext);
	}

	private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
		return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
	}

	/**
	 * 跳过特定行操作
	 *
	 * @param rs 结果集
	 * @param rowBounds 封装分页跳转参数的对象
	 */
	private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
		if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
			//结果集类型不是only  forward
			if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
				//存在从第几行开始的偏移量，直接移动到offset
				rs.absolute(rowBounds.getOffset());
			}
		} else {
			//only forward，就一行行移动
			for (int i = 0; i < rowBounds.getOffset(); i++) {
				if (!rs.next()) {
					break;
				}
			}
		}
	}

	//
	// GET VALUE FROM ROW FOR SIMPLE RESULT MAP
	//

	/**
	 * 解析每一行数据，并封装成resultMap映射对象
	 */
	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix)
		throws SQLException {
		final ResultLoaderMap lazyLoader = new ResultLoaderMap();
		//映射后的结果对象，此时该对象只是个空对象(除了创建该对象的构造器需要传递的参数外)，各个字段还没有值
		Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
		//结果对象不为空且存在对应结果的类型处理器
		if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			//结果对象元数据
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
			boolean foundValues = this.useConstructorMappings;
			//是否开启自动映射
			if (shouldApplyAutomaticMappings(resultMap, false)) {
				//自动映射未指明映射关系的列
				foundValues =
					applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
			}
			//映射填充那些已经指明对应关系的列值
			foundValues =
				applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix)
					|| foundValues;
			foundValues = lazyLoader.size() > 0 || foundValues;
			//foundValues和允许返回null有一个为true，返回上面解析完的rowValue，否则返回null
			rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
		}
		return rowValue;
	}

	/**
	 * 是否启用自动映射
	 */
	private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
		//如果当前<resultMap/>中配置了autoMapping属性，就用当前的配置
		if (resultMap.getAutoMapping() != null) {
			return resultMap.getAutoMapping();
		} else {
			if (isNested) {
				//内嵌需要完全映射，默认PARTIAL
				return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
			} else {
				//非内嵌只要开启自动映射就行
				return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
			}
		}
	}

	//
	// PROPERTY MAPPINGS
	//

	/**
	 * 处理显式映射关系，这里的显式不仅仅是在一个<resultMap/>中直接写明的，还包括<resultMap/>中
	 * 某个字段有时引用另一个<resultMap/>，会将所有嵌套的解析到同一层
	 *
	 * @param rsw 结果集包装对象
	 * @param resultMap <resultMap/>
	 * @param metaObject 结果元数据
	 * @param lazyLoader 延迟加载内容映射
	 * @param columnPrefix 列前缀
	 */
	private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap,
		MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
		throws SQLException {
		//明确写明的映射关系，比如<resultMap/>中的<id>和<result>子标签写明的或者@Select等语句注解上写明的列
		final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
		boolean foundValues = false;
		//获得<resultMap/>中不属于<constructor/>内标签的RequestMapping
		final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
		for (ResultMapping propertyMapping : propertyMappings) {
			//加上可能存在的前缀
			String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			if (propertyMapping.getNestedResultMapId() != null) {
				// the user added a column attribute to a nested result map, ignore it
				column = null;
			}
			if (propertyMapping.isCompositeResult()
				|| (column != null && mappedColumnNames
				.contains(column.toUpperCase(Locale.ENGLISH)))
				|| propertyMapping.getResultSet() != null) {
				//获得属性的值，可能又走了一次完整的查询流程
				Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject,
					propertyMapping, lazyLoader, columnPrefix);
				// issue #541 make property optional
				//属性名称
				final String property = propertyMapping.getProperty();
				//不存在值对应的属性
				if (property == null) {
					continue;
				}
				//属性存在且有值，foundValues为true，表示找到
				else if (value == DEFERED) {
					foundValues = true;
					continue;
				}
				if (value != null) {
					foundValues = true;
				}
				if (value != null || (configuration.isCallSettersOnNulls() && !metaObject
					.getSetterType(property).isPrimitive())) {
					// gcode issue #377, call setter on nulls (value is not 'found')
					//设置属性值
					metaObject.setValue(property, value);
				}
			}
		}
		return foundValues;
	}

	private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject,
		ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
		throws SQLException {
		if (propertyMapping.getNestedQueryId() != null) {
			return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader,
				columnPrefix);
		} else if (propertyMapping.getResultSet() != null) {
			addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
			return DEFERED;
		} else {
			final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
			final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
			return typeHandler.getResult(rs, column);
		}
	}

	private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw,
		ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
		final String mapKey = resultMap.getId() + ":" + columnPrefix;
		//自动映射缓存中是否已经存在mapKey对应的所有映射关系
		List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
		if (autoMapping == null) {
			//不存在从零开始创建
			autoMapping = new ArrayList<>();
			//没映射列的集合
			final List<String> unmappedColumnNames = rsw
				.getUnmappedColumnNames(resultMap, columnPrefix);
			//遍历每一个未映射列
			for (String columnName : unmappedColumnNames) {
				String propertyName = columnName;
				//存在前缀
				if (columnPrefix != null && !columnPrefix.isEmpty()) {
					// When columnPrefix is specified,
					// ignore columns without the prefix.
					//如果以前缀开始，去掉前缀才能真正和嵌套列名对应
					if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
						propertyName = columnName.substring(columnPrefix.length());
					} else {
						continue;
					}
				}
				//根据列名按照驼峰规范转成可能对应的类的属性名
				final String property = metaObject
					.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
				//属性名存在对应的setter方法
				if (property != null && metaObject.hasSetter(property)) {
					//该属性如果已经有过映射记录了，直接返回
					if (resultMap.getMappedProperties().contains(property)) {
						continue;
					}
					//属性setter接受类型
					final Class<?> propertyType = metaObject.getSetterType(property);
					//存在列-属性值的类型处理器
					if (typeHandlerRegistry
						.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
						//获得类型处理器
						final TypeHandler<?> typeHandler = rsw
							.getTypeHandler(propertyType, columnName);
						//记录到未映射缓存列表
						autoMapping.add(
							new UnMappedColumnAutoMapping(columnName, property, typeHandler,
								propertyType.isPrimitive()));
					} else {
						//无类型处理器，获取AutoMappingUnknownColumnBehavior其中的一种类型做处理，默认为NONE，不作处理
						configuration.getAutoMappingUnknownColumnBehavior()
							.doAction(mappedStatement, columnName, property, propertyType);
					}
				} else {
					//同样获取AutoMappingUnknownColumnBehavior其中的一种类型做处理，默认为NONE，不作处理
					configuration.getAutoMappingUnknownColumnBehavior()
						.doAction(mappedStatement, columnName,
							(property != null) ? property : propertyName, null);
				}
			}
			//将自动映射列表放入缓存
			autoMappingsCache.put(mapKey, autoMapping);
		}
		return autoMapping;
	}

	/**
	 * 对于未配置的映射关系的结果，尝试自动映射
	 *
	 * @param rsw 结果集包装对象
	 * @param resultMap ResultMap
	 * @param metaObject 结果元数据
	 * @param columnPrefix 列前缀
	 */
	private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,
		MetaObject metaObject, String columnPrefix) throws SQLException {
		//没有明确指明如何映射的内容集合，每一个UnMappedColumnAutoMapping对应一个未被映射的列-值，
		//其中包含要映射的列名、对应映射实体的字段名、类型处理器、是否普通类型等信息
		List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap,
			metaObject, columnPrefix);
		//映射前假设无法映射
		boolean foundValues = false;
		//存在未映射的内容集合
		if (!autoMapping.isEmpty()) {
			//遍历每一个未映射项，尝试映射
			for (UnMappedColumnAutoMapping mapping : autoMapping) {
				//根据mapping中类型处理器尝试映射对应列的结果
				final Object value = mapping.typeHandler
					.getResult(rsw.getResultSet(), mapping.column);
				if (value != null) {
					//映射成功为true
					foundValues = true;
				}
				//存在值，且非基本类型允许在值为null时调用setter
				if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
					// gcode issue #377, call setter on nulls (value is not 'found')
					//将解析完成的值设置到对应字段
					metaObject.setValue(mapping.property, value);
				}
			}
		}
		return foundValues;
	}

	// MULTIPLE RESULT SETS

	private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue)
		throws SQLException {
		CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping,
			parentMapping.getColumn(), parentMapping.getForeignColumn());
		List<PendingRelation> parents = pendingRelations.get(parentKey);
		if (parents != null) {
			for (PendingRelation parent : parents) {
				if (parent != null && rowValue != null) {
					linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
				}
			}
		}
	}

	private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject,
		ResultMapping parentMapping) throws SQLException {
		CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping,
			parentMapping.getColumn(), parentMapping.getColumn());
		PendingRelation deferLoad = new PendingRelation();
		deferLoad.metaObject = metaResultObject;
		deferLoad.propertyMapping = parentMapping;
		List<PendingRelation> relations = pendingRelations
			.computeIfAbsent(cacheKey, k -> new ArrayList<>());
		// issue #255
		relations.add(deferLoad);
		ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
		if (previous == null) {
			nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
		} else {
			if (!previous.equals(parentMapping)) {
				throw new ExecutorException(
					"Two different properties are mapped to the same resultSet");
			}
		}
	}

	private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping,
		String names, String columns) throws SQLException {
		CacheKey cacheKey = new CacheKey();
		cacheKey.update(resultMapping);
		if (columns != null && names != null) {
			String[] columnsArray = columns.split(",");
			String[] namesArray = names.split(",");
			for (int i = 0; i < columnsArray.length; i++) {
				Object value = rs.getString(columnsArray[i]);
				if (value != null) {
					cacheKey.update(namesArray[i]);
					cacheKey.update(value);
				}
			}
		}
		return cacheKey;
	}

	//
	// INSTANTIATION & CONSTRUCTOR MAPPING
	//

	/**
	 * 根据特定的构造器创建结果对象，如果构造器中存在依赖另一个类的情况，需要嵌套先解析出这个嵌套类
	 * 的值
	 *
	 * @param rsw 结果集包装类
	 * @param resultMap ResultMap
	 * @param lazyLoader 结果加载集合，对于关联属性，如果在不适用的情况下有懒加载的作用
	 * @param columnPrefix 列前缀
	 */
	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap,
		ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
		//复位
		this.useConstructorMappings = false; // reset previous mapping result
		//创建该对象构造器所需参数类型列表
		final List<Class<?>> constructorArgTypes = new ArrayList<>();
		//创建该对象构造器所需参数
		final List<Object> constructorArgs = new ArrayList<>();
		//使用合适构造器创建出对象
		Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes,
			constructorArgs, columnPrefix);
		//结果不为null，并且没有类型处理器能处理，说明是自定义的类型不是常见的普通类型
		if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
			final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
			//遍历每一个ResultMapping，因为其中可能存在嵌套属性，比如Blog中某个字段是Author，那么需要尝试解析Author
			for (ResultMapping propertyMapping : propertyMappings) {
				// issue gcode #109 && issue #149
				//是嵌套列，允许延迟加载
				if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
					//创建结果对象的代理，终于知道为什么开启懒加载后得到的对象都是代理对象
					resultObject = configuration.getProxyFactory()
						.createProxy(resultObject, lazyLoader, configuration, objectFactory,
							constructorArgTypes, constructorArgs);
					break;
				}
			}
		}
		//成功用某个带参构造器创建了对象
		this.useConstructorMappings =
			resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
		return resultObject;
	}

	/**
	 * 创建结果对象
	 *
	 * @param rsw 结果集包装对象
	 * @param resultMap ResultMap
	 * @param constructorArgTypes 构造器参数类型列表
	 * @param constructorArgs 构造器参数列表
	 * @param columnPrefix 列前缀
	 */
	private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
		throws SQLException {
		//<resultMap/>的type属性，标识映射成那个类
		final Class<?> resultType = resultMap.getType();
		//类的元数据
		final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
		//<constructor/>中的映射标签集合
		final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
		//能找到类型处理器，说明是基本类型
		if (hasTypeHandlerForResultObject(rsw, resultType)) {
			//创建基本类型的结果
			return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
		} else if (!constructorMappings.isEmpty()) {
			//存在<constructor/>，根据特定的构造器创建结果
			return createParameterizedResultObject(rsw, resultType, constructorMappings,
				constructorArgTypes, constructorArgs, columnPrefix);
		} else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
			//存在默认构造器，直接创建对象
			return objectFactory.create(resultType);
		} else if (shouldApplyAutomaticMappings(resultMap, false)) {
			//开始自动映射，尝试根据方法签名自动映射创建对象
			return createByConstructorSignature(rsw, resultType, constructorArgTypes,
				constructorArgs, columnPrefix);
		}
		throw new ExecutorException("Do not know how to create an instance of " + resultType);
	}

	/**
	 * 根据参数选择对应的构造器创建对象
	 * 通过解析发现对于<constructor/>内的每一个参数来说，type、typeHandler、select和resultMap四个
	 * 属性必须有一个
	 *
	 * @param rsw 结果集包装对象
	 * @param resultType 当前<resultMap/>要映射成的类
	 * @param constructorMappings <constructor/>对应的每一条标签映射
	 * @param constructorArgTypes 对应构造器的参数类型列表
	 * @param constructorArgs 对应构造器参数列表
	 * @param columnPrefix 列前缀
	 */
	Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType,
		List<ResultMapping> constructorMappings,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
		boolean foundValues = false;
		//遍历每一个构造器映射标签
		for (ResultMapping constructorMapping : constructorMappings) {
			//javaType
			final Class<?> parameterType = constructorMapping.getJavaType();
			//列名
			final String column = constructorMapping.getColumn();
			final Object value;
			try {
				//某个参数又是一个嵌套的对象，该对象是通过select属性指向一个查询语句，然后将参数
				//作为这个查询语句的参数，执行查询操作才能得到这个参数的值
				if (constructorMapping.getNestedQueryId() != null) {
					//解析并创建对应参数的内嵌对象
					value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping,
						columnPrefix);
				}
				//同样是一个嵌套对象，该对象是通过resultMap属性指向另一个<resultMap/>标签的
				else if (constructorMapping.getNestedResultMapId() != null) {
					//得到指向的ResultMap
					final ResultMap resultMap = configuration
						.getResultMap(constructorMapping.getNestedResultMapId());
					//解析出该参数的值
					value = getRowValue(rsw, resultMap, constructorMapping.getColumnPrefix());
				} else {
					//否则就必须有typeHandler属性，标明该参数要谁来处理
					final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
					value = typeHandler
						.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
				}
			}
			//四个属性都没有报错
			catch (ResultMapException | SQLException e) {
				throw new ExecutorException(
					"Could not process result for mapping: " + constructorMapping, e);
			}
			//记录参数类型和参数值
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		//查找constructorArgTypes对应的构造器，传入constructorArgs参数创建对象
		return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs)
			: null;
	}

	/**
	 * 通过筛选构造器创建对象
	 *
	 * @param rsw 结果集包装对象
	 * @param resultType resultMap要映射的java类型
	 * @param constructorArgTypes 构造器参数类型列表
	 * @param constructorArgs 构造器参数列表
	 * @param columnPrefix 列前缀
	 */
	private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
		String columnPrefix) throws SQLException {
		//所有的构造器
		final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
		//查找明显的自动映射构造器
		final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
		if (defaultConstructor != null) {
			//自动映射后创建结果
			return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs,
				columnPrefix, defaultConstructor);
		} else {
			//没有明确指明自动映射构造器，挨个检查能满足自动映射条件的构造器
			for (Constructor<?> constructor : constructors) {
				if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
					//创建结果
					return createUsingConstructor(rsw, resultType, constructorArgTypes,
						constructorArgs, columnPrefix, constructor);
				}
			}
		}
		throw new ExecutorException(
			"No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
	}

	/**
	 * 通过构造器创建对象
	 *
	 * @param rsw 结果集包装类
	 */
	private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix,
		Constructor<?> constructor) throws SQLException {
		boolean foundValues = false;
		//遍历选中的构造器每一个参数
		for (int i = 0; i < constructor.getParameterTypes().length; i++) {
			Class<?> parameterType = constructor.getParameterTypes()[i];
			String columnName = rsw.getColumnNames().get(i);
			//获取列名和参数类型对应的TypeHandler
			TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
			//获得参数值
			Object value = typeHandler
				.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
			//添加参数值、参数类型
			constructorArgTypes.add(parameterType);
			constructorArgs.add(value);
			foundValues = value != null || foundValues;
		}
		//构造器参数类型列表和参数值列表确定唯一的构造器创建对象
		return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs)
			: null;
	}

	/**
	 * 查找和自动映射功能对应的构造器
	 */
	private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
		//只有一个构造器直接返回
		if (constructors.length == 1) {
			return constructors[0];
		}
		//看哪个构造器上有@AutomapConstrucutor
		for (final Constructor<?> constructor : constructors) {
			if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
				return constructor;
			}
		}
		//上面都不满足返回null
		return null;
	}

	/**
	 * 根据构造器每一个参数和每一列jdbcType决定是否可以通过构造器的筛选进行自动映射
	 *
	 * @param constructor 构造器
	 * @param jdbcTypes jdbcType列表
	 */
	private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor,
		final List<JdbcType> jdbcTypes) {
		//构造器参数类型列表
		final Class<?>[] parameterTypes = constructor.getParameterTypes();
		//参数数量必须和列的数量一致
		if (parameterTypes.length != jdbcTypes.size()) {
			return false;
		}
		for (int i = 0; i < parameterTypes.length; i++) {
			//尝试每一列是否都有对应的类型处理器
			if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
				//任何一列没有都无法映射
				return false;
			}
		}
		return true;
	}

	/**
	 * 创建基本类型的结果对象
	 *
	 * @param rsw 结果集包装类
	 * @param resultMap ResultMap
	 * @param columnPrefix 列前缀
	 */
	private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap,
		String columnPrefix) throws SQLException {
		final Class<?> resultType = resultMap.getType();
		final String columnName;
		if (!resultMap.getResultMappings().isEmpty()) {
			/**
			 * 下面的逻辑只有在处理嵌套逻辑时会出现，比如
			 * <resultMap id="addressMapper"
			 *     type="org.apache.ibatis.submitted.column_prefix.Address">
			 *     <constructor>
			 *       <idArg column="id" javaType="int" />
			 *       <arg column="state" javaType="string" />
			 *     </constructor>
			 *     <result property="city" column="city" />
			 *     <result property="hasPhone" column="has_phone" />
			 *     <association property="stateBird" javaType="string">
			 *       <result column="state_bird" />
			 *     </association>
			 *     <association property="zip" resultMap="zipMapper"
			 *       columnPrefix="zip_" />
			 *     <association property="phone1" resultMap="phoneMapper"
			 *       columnPrefix="p1_" />
			 *     <association property="phone2" resultMap="phoneMapper"
			 *       columnPrefix="p2_" />
			 *     <discriminator column="addr_type" javaType="int">
			 *       <case value="1" resultMap="addressWithCautionMapper" />
			 *     </discriminator>
			 *   </resultMap>
			 *  配置中第一个association property="stateBird",是普通类型String
			 */
			final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
			final ResultMapping mapping = resultMappingList.get(0);
			columnName = prependPrefix(mapping.getColumn(), columnPrefix);
		} else {
			columnName = rsw.getColumnNames().get(0);
		}
		//因为是普通类型肯定是有类型处理器的
		final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
		//类型处理器处理得到对应列的值
		return typeHandler.getResult(rsw.getResultSet(), columnName);
	}

	//
	// NESTED QUERY
	//

	/**
	 * 结果集通过<constructor/>的封装获得，但是<constructor/>中存在某一属性需要通过再查询另外的语句得到
	 *
	 * @param rs 结果集
	 * @param constructorMapping <constructor/>中那有select属性的一对映射，select对应一条查询语句
	 * @param columnPrefix 列前缀
	 */
	private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping,
		String columnPrefix) throws SQLException {
		//select对应查询语句标签id
		final String nestedQueryId = constructorMapping.getNestedQueryId();
		//查询语句对应的MappedStatement
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		//查询语句的参数类型
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		//获得参数的值
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs,
			constructorMapping, nestedQueryParameterType, columnPrefix);
		Object value = null;
		if (nestedQueryParameterObject != null) {
			//内嵌语句的BoundSql
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			//创建缓存key
			final CacheKey key = executor
				.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
					nestedBoundSql);
			//内嵌语句对应的javaType
			final Class<?> targetType = constructorMapping.getJavaType();
			//延迟加载器
			final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery,
				nestedQueryParameterObject, targetType, key, nestedBoundSql);
			//加载嵌套语句执行的值
			value = resultLoader.loadResult();
		}
		return value;
	}

	private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject,
		ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
		throws SQLException {
		final String nestedQueryId = propertyMapping.getNestedQueryId();
		final String property = propertyMapping.getProperty();
		final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
		final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
		final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs,
			propertyMapping, nestedQueryParameterType, columnPrefix);
		Object value = null;
		if (nestedQueryParameterObject != null) {
			final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
			final CacheKey key = executor
				.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT,
					nestedBoundSql);
			final Class<?> targetType = propertyMapping.getJavaType();
			if (executor.isCached(nestedQuery, key)) {
				executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
				value = DEFERED;
			} else {
				final ResultLoader resultLoader = new ResultLoader(configuration, executor,
					nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
				if (propertyMapping.isLazy()) {
					lazyLoader.addLoader(property, metaResultObject, resultLoader);
					value = DEFERED;
				} else {
					value = resultLoader.loadResult();
				}
			}
		}
		return value;
	}

	/**
	 * 获取嵌套resultMap或者语句标签的值
	 *
	 * @param rs 结果集
	 * @param resultMapping 内嵌标签的ResultMapping
	 * @param parameterType 嵌套的语句标签可能需要一些参数，参数的类型
	 * @param columnPrefix 列前缀
	 */
	private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping,
		Class<?> parameterType, String columnPrefix) throws SQLException {
		if (resultMapping.isCompositeResult()) {
			//处理多列
			return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
		} else {
			//单列
			return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
		}
	}

	private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping,
		Class<?> parameterType, String columnPrefix) throws SQLException {
		final TypeHandler<?> typeHandler;
		if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
			//内嵌列是简单类型，直接获取类型处理器
			typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
		} else {
			//未知类型处理器
			typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
		}
		//解析出嵌套列的值
		return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
	}

	private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping,
		Class<?> parameterType, String columnPrefix) throws SQLException {
		//根据参数类型创建一个空的总参数对象
		final Object parameterObject = instantiateParameterObject(parameterType);
		//空参数对象的元对象，为了解析操作方便
		final MetaObject metaObject = configuration.newMetaObject(parameterObject);
		boolean foundValues = false;
		//组合列，遍历每一个列的映射
		for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
			//总参数对象中对应其中一个参数的setter接受的java类型
			final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
			//对应的类型处理器
			final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
			//解析出对象值
			final Object propValue = typeHandler
				.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
			// issue #353 & #560 do not execute nested query if key is null
			if (propValue != null) {
				//对象值存在就调用总参数对象元数据塞入该值
				metaObject.setValue(innerResultMapping.getProperty(), propValue);
				foundValues = true;
			}
		}
		return foundValues ? parameterObject : null;
	}

	/**
	 * 创建参数类型的空对象
	 */
	private Object instantiateParameterObject(Class<?> parameterType) {
		if (parameterType == null) {
			return new HashMap<>();
		} else if (ParamMap.class.equals(parameterType)) {
			return new HashMap<>(); // issue #649
		} else {
			return objectFactory.create(parameterType);
		}
	}

	//
	// DISCRIMINATOR
	//

	/**
	 * 处理<resultMap/>内存在的<discriminator/>
	 */
	public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap,
		String columnPrefix) throws SQLException {
		Set<String> pastDiscriminators = new HashSet<>();
		Discriminator discriminator = resultMap.getDiscriminator();
		while (discriminator != null) {
			//<discriminator/>解析后对应的值
			final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
			//选中的那个<case/>对应resultMap的id
			final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
			if (configuration.hasResultMap(discriminatedMapId)) {
				//存在对应的ResultMap
				resultMap = configuration.getResultMap(discriminatedMapId);
				Discriminator lastDiscriminator = discriminator;
				//从resultMap中再获得一次
				discriminator = resultMap.getDiscriminator();
				//唯一一个，调出返回
				if (discriminator == lastDiscriminator || !pastDiscriminators
					.add(discriminatedMapId)) {
					break;
				}
			} else {
				break;
			}
		}
		return resultMap;
	}

	/**
	 * 获取<discriminator/>修饰的列的真实值
	 *
	 * @param rs 结果集
	 * @param discriminator <discriminator/>对象
	 * @param columnPrefix 列前缀
	 */
	private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator,
		String columnPrefix) throws SQLException {
		//<discriminator/>对应的RequestMapping对象
		final ResultMapping resultMapping = discriminator.getResultMapping();
		//<discriminator/>对应列的类型处理器
		final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
		//得到<discriminator/>列的值
		return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
	}

	/**
	 * 前缀加列名，任何一个为空都返回原始列名
	 *
	 * @param columnName 列名
	 * @param prefix 前缀
	 */
	private String prependPrefix(String columnName, String prefix) {
		if (columnName == null || columnName.length() == 0 || prefix == null
			|| prefix.length() == 0) {
			return columnName;
		}
		return prefix + columnName;
	}

	//
	// HANDLE NESTED RESULT MAPS
	//

	/**
	 * 处理ResultSet和ResultMap非一一映射的情况，<resultMap/>中存在嵌套的<resultMap/>
	 *
	 * @param rsw ResultSet包装对象
	 * @param resultMap ResultMap
	 * @param resultHandler 结果处理器
	 * @param rowBounds 封装分页的对象
	 * @param parentMapping 父ResultMapping
	 */
	private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
		ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
		throws SQLException {
		final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
		ResultSet resultSet = rsw.getResultSet();
		//跳到某行
		skipRows(resultSet, rowBounds);
		Object rowValue = previousRowValue;
		while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet
			.next()) {
			//<discriminator/>对应ResultMap对象
			final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet,
				resultMap, null);
			final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
			Object partialObject = nestedResultObjects.get(rowKey);
			// issue #577 && #542
			if (mappedStatement.isResultOrdered()) {
				if (partialObject == null && rowValue != null) {
					nestedResultObjects.clear();
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
			} else {
				rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
				if (partialObject == null) {
					storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
				}
			}
		}
		if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(
			resultContext, rowBounds)) {
			storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
			previousRowValue = null;
		} else if (rowValue != null) {
			previousRowValue = rowValue;
		}
	}

	//
	// GET VALUE FROM ROW FOR NESTED RESULT MAP
	//

	private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey,
		String columnPrefix, Object partialObject) throws SQLException {
		final String resultMapId = resultMap.getId();
		Object rowValue = partialObject;
		if (rowValue != null) {
			final MetaObject metaObject = configuration.newMetaObject(rowValue);
			putAncestor(rowValue, resultMapId);
			applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
			ancestorObjects.remove(resultMapId);
		} else {
			final ResultLoaderMap lazyLoader = new ResultLoaderMap();
			//获得一行映射的对象
			rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
			//存在列对象，但没有该对象的类型处理器，说明该对象不是常见类型的映射
			if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
				//列对应对象元数据
				final MetaObject metaObject = configuration.newMetaObject(rowValue);
				//是否使用<constructor/>
				boolean foundValues = this.useConstructorMappings;
				//是否自动映射
				if (shouldApplyAutomaticMappings(resultMap, true)) {
					foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix)
						|| foundValues;
				}
				foundValues =
					applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix)
						|| foundValues;
				putAncestor(rowValue, resultMapId);
				foundValues =
					applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey,
						true) || foundValues;
				ancestorObjects.remove(resultMapId);
				foundValues = lazyLoader.size() > 0 || foundValues;
				rowValue =
					foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
			}
			if (combinedKey != CacheKey.NULL_CACHE_KEY) {
				nestedResultObjects.put(combinedKey, rowValue);
			}
		}
		return rowValue;
	}

	private void putAncestor(Object resultObject, String resultMapId) {
		ancestorObjects.put(resultMapId, resultObject);
	}

	//
	// NESTED RESULT MAP (JOIN MAPPING)
	//

	private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap,
		MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
		boolean foundValues = false;
		for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
			final String nestedResultMapId = resultMapping.getNestedResultMapId();
			if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
				try {
					final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
					final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(),
						nestedResultMapId, columnPrefix);
					if (resultMapping.getColumnPrefix() == null) {
						// try to fill circular reference only when columnPrefix
						// is not specified for the nested result map (issue #215)
						Object ancestorObject = ancestorObjects.get(nestedResultMapId);
						if (ancestorObject != null) {
							if (newObject) {
								linkObjects(metaObject, resultMapping,
									ancestorObject); // issue #385
							}
							continue;
						}
					}
					final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
					final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
					Object rowValue = nestedResultObjects.get(combinedKey);
					boolean knownValue = rowValue != null;
					instantiateCollectionPropertyIfAppropriate(resultMapping,
						metaObject); // mandatory
					if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
						rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix,
							rowValue);
						if (rowValue != null && !knownValue) {
							linkObjects(metaObject, resultMapping, rowValue);
							foundValues = true;
						}
					}
				} catch (SQLException e) {
					throw new ExecutorException(
						"Error getting nested result map values for '" + resultMapping.getProperty()
							+ "'.  Cause: " + e, e);
				}
			}
		}
		return foundValues;
	}

	private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
		final StringBuilder columnPrefixBuilder = new StringBuilder();
		if (parentPrefix != null) {
			columnPrefixBuilder.append(parentPrefix);
		}
		if (resultMapping.getColumnPrefix() != null) {
			columnPrefixBuilder.append(resultMapping.getColumnPrefix());
		}
		return columnPrefixBuilder.length() == 0 ? null
			: columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
	}

	private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix,
		ResultSetWrapper rsw) throws SQLException {
		Set<String> notNullColumns = resultMapping.getNotNullColumns();
		if (notNullColumns != null && !notNullColumns.isEmpty()) {
			ResultSet rs = rsw.getResultSet();
			for (String column : notNullColumns) {
				rs.getObject(prependPrefix(column, columnPrefix));
				if (!rs.wasNull()) {
					return true;
				}
			}
			return false;
		} else if (columnPrefix != null) {
			for (String columnName : rsw.getColumnNames()) {
				if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId,
		String columnPrefix) throws SQLException {
		ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
		return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
	}

	//
	// UNIQUE RESULT KEY
	//

	private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix)
		throws SQLException {
		final CacheKey cacheKey = new CacheKey();
		cacheKey.update(resultMap.getId());
		List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
		if (resultMappings.isEmpty()) {
			if (Map.class.isAssignableFrom(resultMap.getType())) {
				createRowKeyForMap(rsw, cacheKey);
			} else {
				createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
			}
		} else {
			createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
		}
		if (cacheKey.getUpdateCount() < 2) {
			return CacheKey.NULL_CACHE_KEY;
		}
		return cacheKey;
	}

	private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
		if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
			CacheKey combinedKey;
			try {
				combinedKey = rowKey.clone();
			} catch (CloneNotSupportedException e) {
				throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
			}
			combinedKey.update(parentRowKey);
			return combinedKey;
		}
		return CacheKey.NULL_CACHE_KEY;
	}

	private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
		List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
		if (resultMappings.isEmpty()) {
			resultMappings = resultMap.getPropertyResultMappings();
		}
		return resultMappings;
	}

	private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw,
		CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix)
		throws SQLException {
		for (ResultMapping resultMapping : resultMappings) {
			if (resultMapping.getNestedResultMapId() != null
				&& resultMapping.getResultSet() == null) {
				// Issue #392
				final ResultMap nestedResultMap = configuration
					.getResultMap(resultMapping.getNestedResultMapId());
				createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey,
					nestedResultMap.getConstructorResultMappings(),
					prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
			} else if (resultMapping.getNestedQueryId() == null) {
				final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
				final TypeHandler<?> th = resultMapping.getTypeHandler();
				List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
				// Issue #114
				if (column != null && mappedColumnNames
					.contains(column.toUpperCase(Locale.ENGLISH))) {
					final Object value = th.getResult(rsw.getResultSet(), column);
					if (value != null || configuration.isReturnInstanceForEmptyRow()) {
						cacheKey.update(column);
						cacheKey.update(value);
					}
				}
			}
		}
	}

	private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw,
		CacheKey cacheKey, String columnPrefix) throws SQLException {
		final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
		List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
		for (String column : unmappedColumnNames) {
			String property = column;
			if (columnPrefix != null && !columnPrefix.isEmpty()) {
				// When columnPrefix is specified, ignore columns without the prefix.
				if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
					property = column.substring(columnPrefix.length());
				} else {
					continue;
				}
			}
			if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase())
				!= null) {
				String value = rsw.getResultSet().getString(column);
				if (value != null) {
					cacheKey.update(column);
					cacheKey.update(value);
				}
			}
		}
	}

	private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
		List<String> columnNames = rsw.getColumnNames();
		for (String columnName : columnNames) {
			final String value = rsw.getResultSet().getString(columnName);
			if (value != null) {
				cacheKey.update(columnName);
				cacheKey.update(value);
			}
		}
	}

	private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
		final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping,
			metaObject);
		if (collectionProperty != null) {
			final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
			targetMetaObject.add(rowValue);
		} else {
			metaObject.setValue(resultMapping.getProperty(), rowValue);
		}
	}

	private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping,
		MetaObject metaObject) {
		final String propertyName = resultMapping.getProperty();
		Object propertyValue = metaObject.getValue(propertyName);
		if (propertyValue == null) {
			Class<?> type = resultMapping.getJavaType();
			if (type == null) {
				type = metaObject.getSetterType(propertyName);
			}
			try {
				if (objectFactory.isCollection(type)) {
					propertyValue = objectFactory.create(type);
					metaObject.setValue(propertyName, propertyValue);
					return propertyValue;
				}
			} catch (Exception e) {
				throw new ExecutorException(
					"Error instantiating collection property for result '" + resultMapping
						.getProperty() + "'.  Cause: " + e, e);
			}
		} else if (objectFactory.isCollection(propertyValue.getClass())) {
			return propertyValue;
		}
		return null;
	}

	/**
	 * 根据结果集和结果类型判断是否存在对应的结果处理器
	 *
	 * @param rsw 结果集包装对象
	 * @param resultType 结果类型
	 */
	private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
		//单值，通过列名和结果类型共同得到类型处理器
		if (rsw.getColumnNames().size() == 1) {
			return typeHandlerRegistry
				.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
		}
		//复合类型，比如一个自定义对象，只能根据结果类型获取类型处理器
		//一般来说TypeHandlerRegistry中初始化了常见的类型转换器，如果是自定义对象是没有对应的类型转换器的
		return typeHandlerRegistry.hasTypeHandler(resultType);
	}

}

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
package org.apache.ibatis.executor.keygen;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ArrayUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

	/**
	 * A shared instance.
	 *
	 * @since 3.4.3
	 */
	public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

	@Override
	public void processBefore(Executor executor, MappedStatement ms, Statement stmt,
		Object parameter) {
		// do nothing
	}

	@Override
	public void processAfter(Executor executor, MappedStatement ms, Statement stmt,
		Object parameter) {
		processBatch(ms, stmt, parameter);
	}

	/**
	 * @param parameter sql语句参数，在DefaultSqlSession.wrapCollection中将Collection类型的parameter
	 * 包装成了两个，两个同值参数名分别为collection和list，如果是普通对象参数，就是简单的透传
	 */
	public void processBatch(MappedStatement ms, Statement stmt, Object parameter) {
		//生成列名名列表
		final String[] keyProperties = ms.getKeyProperties();
		if (keyProperties == null || keyProperties.length == 0) {
			return;
		}
		ResultSet rs = null;
		try {
			//根据不同的数据库得到生成列的值结果集
			rs = stmt.getGeneratedKeys();
			final Configuration configuration = ms.getConfiguration();
			//生成的数量必须大于等于要生成参数的数量，猜想某些数据库驱动下列对应多值？
			if (rs.getMetaData().getColumnCount() >= keyProperties.length) {
				//获取唯一确定的参数
				Object soleParam = getSoleParameter(parameter);
				if (soleParam != null) {
					//从rs中将生成的值放入soleParam的对应字段中
					assignKeysToParam(configuration, rs, keyProperties, soleParam);
				} else {
					//从多个不同的参数中找到和待生成的字段相匹配的内容并赋值
					assignKeysToOneOfParams(configuration, rs, keyProperties,
						(Map<?, ?>) parameter);
				}
			}
		} catch (Exception e) {
			throw new ExecutorException(
				"Error getting generated key or setting result to parameter object. Cause: " + e,
				e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (Exception e) {
					// ignore
				}
			}
		}
	}

	/**
	 * 在多个不同类型的参数中选择一个，以满足xxx.yy的表达式，从而选择出yy对应的值
	 *
	 * @param configuration 配置文件
	 * @param rs 携带数据库驱动生成值的结果集
	 * @param keyProperties 所有待生成字段表达式集合
	 * @param paramMap 参数映射
	 */
	protected void assignKeysToOneOfParams(final Configuration configuration, ResultSet rs,
		final String[] keyProperties,
		Map<?, ?> paramMap) throws SQLException {
		// Assuming 'keyProperty' includes the parameter name. e.g. 'param.id'.
		//必须存在xx.yy的情况，因为多个参数需要以.之前的部分决定是哪个参数值的内容
		int firstDot = keyProperties[0].indexOf('.');
		if (firstDot == -1) {
			throw new ExecutorException(
				"Could not determine which parameter to assign generated keys to. "
					+ "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
					+ "Specified key properties are " + ArrayUtil.toString(keyProperties)
					+ " and available parameters are "
					+ paramMap.keySet());
		}
		//.前面的内容
		String paramName = keyProperties[0].substring(0, firstDot);
		Object param;
		//对于不是普通对象、集合和数组的多种类型参数，实际类型为ParamMap，其实际是一个以每一个参数名为key，参数值为value的Map
		//参数中存在
		if (paramMap.containsKey(paramName)) {
			//获得参数值
			param = paramMap.get(paramName);
		} else {
			throw new ExecutorException("Could not find parameter '" + paramName + "'. "
				+ "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
				+ "Specified key properties are " + ArrayUtil.toString(keyProperties)
				+ " and available parameters are "
				+ paramMap.keySet());
		}
		// Remove param name from 'keyProperty' string. e.g. 'param.id' -> 'id'
		//可能存在多种类型的待生成值
		String[] modifiedKeyProperties = new String[keyProperties.length];
		for (int i = 0; i < keyProperties.length; i++) {
			//每一种都必须保证存在.，以及必须以已存在的参数名开头
			if (keyProperties[i].charAt(firstDot) == '.' && keyProperties[i]
				.startsWith(paramName)) {
				//.后面真正要生成的字段名称，比如country.id中的id
				modifiedKeyProperties[i] = keyProperties[i].substring(firstDot + 1);
			} else {
				throw new ExecutorException(
					"Assigning generated keys to multiple parameters is not supported. "
						+ "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
						+ "Specified key properties are " + ArrayUtil.toString(keyProperties)
						+ " and available parameters are "
						+ paramMap.keySet());
			}
		}
		//设置生成的值
		assignKeysToParam(configuration, rs, modifiedKeyProperties, param);
	}

	/**
	 * 将数据库生成的列值设置到对应的sql参数上
	 *
	 * @param rs 根据不同数据库驱动生成的列值结果集
	 * @param keyProperties 需要生成列的列名
	 * @param param 传入的sql的唯一确定参数值
	 */
	private void assignKeysToParam(final Configuration configuration, ResultSet rs,
		final String[] keyProperties,
		Object param)
		throws SQLException {
		//获得类型处理器注册器
		final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		//不同数据库驱动实现的结果集元数据
		final ResultSetMetaData rsmd = rs.getMetaData();
		// Wrap the parameter in Collection to normalize the logic.
		//该方法param只会有普通类型、集合和数组三种，将三种类型都转成List统一处理
		Collection<?> paramAsCollection = null;
		if (param instanceof Object[]) {
			paramAsCollection = Arrays.asList((Object[]) param);
		} else if (!(param instanceof Collection)) {
			paramAsCollection = Arrays.asList(param);
		} else {
			paramAsCollection = (Collection<?>) param;
		}
		TypeHandler<?>[] typeHandlers = null;
		//参数中存在某一列是需要数据库生成的，比如User中的id需要数据库生成
		for (Object obj : paramAsCollection) {
			if (!rs.next()) {
				break;
			}
			//参数值元数据
			MetaObject metaParam = configuration.newMetaObject(obj);
			if (typeHandlers == null) {
				//根据主键生成值和参数值决定类型处理器
				typeHandlers = getTypeHandlers(typeHandlerRegistry, metaParam, keyProperties, rsmd);
			}
			//填充参数中需要生成的字段
			populateKeys(rs, metaParam, keyProperties, typeHandlers);
		}
	}

	private Object getSoleParameter(Object parameter) {
		//普通的参数直接返回
		if (!(parameter instanceof ParamMap || parameter instanceof StrictMap)) {
			return parameter;
		}
		//多个参数封装成的ParamMap、集合数组对应的StrictMap单独处理
		Object soleParam = null;
		for (Object paramValue : ((Map<?, ?>) parameter).values()) {
			if (soleParam == null) {
				//对于StrictMap这种将同一个集合封装两份，实际上两份都是一样的，都是整个集合列表
				soleParam = paramValue;
			} else if (soleParam != paramValue) {
				//多个不同的参数返回null
				soleParam = null;
				break;
			}
		}
		return soleParam;
	}

	/**
	 * 根据参数元数据、数据库生成值和类型处理器注册器选择符合的类型处理器
	 *
	 * @param typeHandlerRegistry 类型处理器注册器
	 * @param metaParam 参数元数据
	 * @param keyProperties 需要数据库生成列的列名
	 * @param rsmd 数据库生成列元数据
	 */
	private TypeHandler<?>[] getTypeHandlers(TypeHandlerRegistry typeHandlerRegistry,
		MetaObject metaParam, String[] keyProperties, ResultSetMetaData rsmd) throws SQLException {
		//有几个要生成的列就需要几个类型处理器
		TypeHandler<?>[] typeHandlers = new TypeHandler<?>[keyProperties.length];
		for (int i = 0; i < keyProperties.length; i++) {
			//每一个待生成的列是否存在setter
			if (metaParam.hasSetter(keyProperties[i])) {
				//获得setter参数的java类型
				Class<?> keyPropertyType = metaParam.getSetterType(keyProperties[i]);
				//根据javaType和jdbcType得到类型处理器
				typeHandlers[i] = typeHandlerRegistry
					.getTypeHandler(keyPropertyType, JdbcType.forCode(rsmd.getColumnType(i + 1)));
			} else {
				throw new ExecutorException(
					"No setter found for the keyProperty '" + keyProperties[i] + "' in '"
						+ metaParam.getOriginalObject().getClass().getName() + "'.");
			}
		}
		return typeHandlers;
	}

	/**
	 * 填充生成的列值
	 *
	 * @param rs 数据库生成列结果集
	 * @param metaParam 参数值元数据
	 * @param keyProperties 要生成列名
	 * @param typeHandlers 要生成列的类型处理器数组
	 */
	private void populateKeys(ResultSet rs, MetaObject metaParam, String[] keyProperties,
		TypeHandler<?>[] typeHandlers) throws SQLException {
		for (int i = 0; i < keyProperties.length; i++) {
			//列名/属性名
			String property = keyProperties[i];
			//对应处理器
			TypeHandler<?> th = typeHandlers[i];
			if (th != null) {
				//从数据库结果集中拿出对应列的生成值
				Object value = th.getResult(rs, i + 1);
				//调用对应属性的setter塞进去
				metaParam.setValue(property, value);
			}
		}
	}

}

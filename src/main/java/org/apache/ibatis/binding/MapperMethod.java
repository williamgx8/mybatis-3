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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 对应每一个Mapper方法
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

	private final SqlCommand command;
	private final MethodSignature method;

	public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
		this.command = new SqlCommand(config, mapperInterface, method);
		this.method = new MethodSignature(config, mapperInterface, method);
	}

	/**
	 * 使用SqlSession并根据语句类型执行sql
	 *
	 * @param sqlSession SqlSession
	 * @param args sql语句的参数值数组
	 */
	public Object execute(SqlSession sqlSession, Object[] args) {
		Object result;
		switch (command.getType()) {
			case INSERT: {
				//将参数名和参数值对应
				Object param = method.convertArgsToSqlCommandParam(args);
				//执行insert操作，返回影响的行数
				result = rowCountResult(sqlSession.insert(command.getName(), param));
				break;
			}
			case UPDATE: {
				//转换参数
				Object param = method.convertArgsToSqlCommandParam(args);
				//执行更新，返回影响的行数
				result = rowCountResult(sqlSession.update(command.getName(), param));
				break;
			}
			case DELETE: {
				//转换参数
				Object param = method.convertArgsToSqlCommandParam(args);
				//执行更新，返回影响的行数
				result = rowCountResult(sqlSession.delete(command.getName(), param));
				break;
			}
			case SELECT:
				//方法返回null，并且方法参数中存在ResultHandler
				if (method.returnsVoid() && method.hasResultHandler()) {
					executeWithResultHandler(sqlSession, args);
					result = null;
					//返回集合或数组
				} else if (method.returnsMany()) {
					result = executeForMany(sqlSession, args);
					//返回Map
				} else if (method.returnsMap()) {
					result = executeForMap(sqlSession, args);
					//返回游标
				} else if (method.returnsCursor()) {
					result = executeForCursor(sqlSession, args);
				} else {
					Object param = method.convertArgsToSqlCommandParam(args);
					//执行单个查询
					result = sqlSession.selectOne(command.getName(), param);
					//
					if (method.returnsOptional() &&
						(result == null || !method.getReturnType().equals(result.getClass()))) {
						result = Optional.ofNullable(result);
					}
				}
				break;
			case FLUSH:
				result = sqlSession.flushStatements();
				break;
			default:
				throw new BindingException("Unknown execution method for: " + command.getName());
		}
		if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
			throw new BindingException("Mapper method '" + command.getName()
				+ " attempted to return null from a method with a primitive return type (" + method
				.getReturnType() + ").");
		}
		return result;
	}

	/**
	 * 根据解析的method的ReturnType和执行SQL后的返回的影响的行数，进行转换
	 *
	 * @param rowCount SQL执行影响的行数
	 */
	private Object rowCountResult(int rowCount) {
		final Object result;
		if (method.returnsVoid()) {
			result = null;
		} else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE
			.equals(method.getReturnType())) {
			result = rowCount;
		} else if (Long.class.equals(method.getReturnType()) || Long.TYPE
			.equals(method.getReturnType())) {
			result = (long) rowCount;
		} else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE
			.equals(method.getReturnType())) {
			result = rowCount > 0;
		} else {
			throw new BindingException(
				"Mapper method '" + command.getName() + "' has an unsupported return type: "
					+ method.getReturnType());
		}
		return result;
	}

	/**
	 * 如果方法参数有一个为ResultHandler，说明该sql执行的结果需要通过这个ResultHandler参数进行处理
	 */
	private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
		//根据Mapper唯一标示找对对应的MappedStatement
		MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
		//存储过程相关
		if (!StatementType.CALLABLE.equals(ms.getStatementType())
			&& void.class.equals(ms.getResultMaps().get(0).getType())) {
			throw new BindingException("method " + command.getName()
				+ " needs either a @ResultMap annotation, a @ResultType annotation,"
				+ " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
		}
		//转换参数为一个值
		Object param = method.convertArgsToSqlCommandParam(args);
		if (method.hasRowBounds()) {
			//如果存在RowBounds类型的参数取出来
			RowBounds rowBounds = method.extractRowBounds(args);
			//执行select
			sqlSession
				.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
		} else {
			//执行select
			sqlSession.select(command.getName(), param, method.extractResultHandler(args));
		}
	}

	/**
	 * 执行返回值为多条数据的sql
	 */
	private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
		List<E> result;
		//转换参数
		Object param = method.convertArgsToSqlCommandParam(args);
		if (method.hasRowBounds()) {
			//存在分页对象取出
			RowBounds rowBounds = method.extractRowBounds(args);
			//执行selectList
			result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
		} else {
			//执行selectList
			result = sqlSession.<E>selectList(command.getName(), param);
		}
		// issue #510 Collections & arrays support
		//如果所需要的类型不是List的父类
		if (!method.getReturnType().isAssignableFrom(result.getClass())) {
			//所需要的返回值为数组类型
			if (method.getReturnType().isArray()) {
				//将List转成数组
				return convertToArray(result);
			} else {
				//转换成其他声明的集合对象，比如set
				return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
			}
		}
		return result;
	}

	private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
		Cursor<T> result;
		Object param = method.convertArgsToSqlCommandParam(args);
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
		} else {
			result = sqlSession.<T>selectCursor(command.getName(), param);
		}
		return result;
	}

	/**
	 * 转换成其他集合类型
	 */
	private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
		//创建返回类型的空对象
		Object collection = config.getObjectFactory().create(method.getReturnType());
		//返回对象的元对象
		MetaObject metaObject = config.newMetaObject(collection);
		//将结果list放入
		metaObject.addAll(list);
		return collection;
	}

	@SuppressWarnings("unchecked")
	private <E> Object convertToArray(List<E> list) {
		//数组每个元素类型
		Class<?> arrayComponentType = method.getReturnType().getComponentType();
		//创建长度为list大小类型为arrayComponentType的数组
		Object array = Array.newInstance(arrayComponentType, list.size());
		if (arrayComponentType.isPrimitive()) {
			//如果是普通类型，遍历每一个set
			for (int i = 0; i < list.size(); i++) {
				Array.set(array, i, list.get(i));
			}
			return array;
		} else {
			//否则直接调用toArray方法
			return list.toArray((E[]) array);
		}
	}

	private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
		Map<K, V> result;
		Object param = method.convertArgsToSqlCommandParam(args);
		if (method.hasRowBounds()) {
			RowBounds rowBounds = method.extractRowBounds(args);
			result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(),
				rowBounds);
		} else {
			result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
		}
		return result;
	}

	public static class ParamMap<V> extends HashMap<String, V> {

		private static final long serialVersionUID = -2212268410512043556L;

		@Override
		public V get(Object key) {
			if (!super.containsKey(key)) {
				throw new BindingException(
					"Parameter '" + key + "' not found. Available parameters are " + keySet());
			}
			return super.get(key);
		}

	}

	public static class SqlCommand {

		//可以理解为唯一标识，一般为sql对应MapperStatement的id
		private final String name;
		//SELECT UPDATE这些，sql的类型
		private final SqlCommandType type;

		public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
			//Mapper中方法名
			final String methodName = method.getName();
			//Mapper
			final Class<?> declaringClass = method.getDeclaringClass();
			//Mapper和调用方法解析成MappedStatement
			MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
				configuration);
			if (ms == null) {
				//存在@Flush注解
				if (method.getAnnotation(Flush.class) != null) {
					name = null;
					type = SqlCommandType.FLUSH;
				} else {
					throw new BindingException("Invalid bound statement (not found): "
						+ mapperInterface.getName() + "." + methodName);
				}
			} else {
				//解析MappedStatement成功，设置值
				name = ms.getId();
				type = ms.getSqlCommandType();
				if (type == SqlCommandType.UNKNOWN) {
					throw new BindingException("Unknown execution method for: " + name);
				}
			}
		}

		public String getName() {
			return name;
		}

		public SqlCommandType getType() {
			return type;
		}

		/**
		 * 将Mapper中对应方法解析成MappedStatement
		 *
		 * @param mapperInterface Mapper类型
		 * @param methodName 方法
		 * @param declaringClass 声明方法的类
		 */
		private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
			Class<?> declaringClass, Configuration configuration) {
			//生成唯一标识
			String statementId = mapperInterface.getName() + "." + methodName;
			//已存在，直接从mappedStatements中拿出返回
			if (configuration.hasStatement(statementId)) {
				return configuration.getMappedStatement(statementId);
				//已经找到顶了，那肯定就没有了
			} else if (mapperInterface.equals(declaringClass)) {
				return null;
			}
			//遍历父接口
			for (Class<?> superInterface : mapperInterface.getInterfaces()) {
				//declaringClass是superInterface的子类
				if (declaringClass.isAssignableFrom(superInterface)) {
					//递归往上找
					MappedStatement ms = resolveMappedStatement(superInterface, methodName,
						declaringClass, configuration);
					if (ms != null) {
						return ms;
					}
				}
			}
			return null;
		}
	}

	public static class MethodSignature {

		private final boolean returnsMany;
		private final boolean returnsMap;
		private final boolean returnsVoid;
		private final boolean returnsCursor;
		private final boolean returnsOptional;
		private final Class<?> returnType;
		private final String mapKey;
		private final Integer resultHandlerIndex;
		private final Integer rowBoundsIndex;
		private final ParamNameResolver paramNameResolver;

		public MethodSignature(Configuration configuration, Class<?> mapperInterface,
			Method method) {
			//解析method在mapperInterface中的真实返回值
			Type resolvedReturnType = TypeParameterResolver
				.resolveReturnType(method, mapperInterface);
			//普通类
			if (resolvedReturnType instanceof Class<?>) {
				this.returnType = (Class<?>) resolvedReturnType;
				//带泛型，获得原始类型
			} else if (resolvedReturnType instanceof ParameterizedType) {
				this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
			} else {
				this.returnType = method.getReturnType();
			}
			//是否返回null
			this.returnsVoid = void.class.equals(this.returnType);
			//是否返回集合或者数组
			this.returnsMany =
				configuration.getObjectFactory().isCollection(this.returnType) || this.returnType
					.isArray();
			//游标类
			this.returnsCursor = Cursor.class.equals(this.returnType);
			//Optional
			this.returnsOptional = Optional.class.equals(this.returnType);
			//@MapKey上配置的值
			this.mapKey = getMapKey(method);
			//是否返回Map
			this.returnsMap = this.mapKey != null;
			//获取方法参数中RowBounds类型参数的位置
			this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
			//获取方法参数中ResultHandler类型参数的位置
			this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
			//参数解析器
			this.paramNameResolver = new ParamNameResolver(configuration, method);
		}

		/**
		 * 将参数值进行转换：
		 * 1. 无参数返回null
		 * 2. 一个参数返回参数本身
		 * 3. 多个参数返回ParamMap对象，key为参数名，value为参数值
		 */
		public Object convertArgsToSqlCommandParam(Object[] args) {
			return paramNameResolver.getNamedParams(args);
		}

		public boolean hasRowBounds() {
			return rowBoundsIndex != null;
		}

		public RowBounds extractRowBounds(Object[] args) {
			return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
		}

		public boolean hasResultHandler() {
			return resultHandlerIndex != null;
		}

		public ResultHandler extractResultHandler(Object[] args) {
			return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
		}

		public String getMapKey() {
			return mapKey;
		}

		public Class<?> getReturnType() {
			return returnType;
		}

		public boolean returnsMany() {
			return returnsMany;
		}

		public boolean returnsMap() {
			return returnsMap;
		}

		public boolean returnsVoid() {
			return returnsVoid;
		}

		public boolean returnsCursor() {
			return returnsCursor;
		}

		/**
		 * return whether return type is {@code java.util.Optional}
		 *
		 * @return return {@code true}, if return type is {@code java.util.Optional}
		 * @since 3.5.0
		 */
		public boolean returnsOptional() {
			return returnsOptional;
		}

		/**
		 * 查询method参数中类型为paramType的那个唯一的index
		 */
		private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
			Integer index = null;
			//方法参数
			final Class<?>[] argTypes = method.getParameterTypes();
			for (int i = 0; i < argTypes.length; i++) {
				//一个个遍历，查找到给定Class的子类
				if (paramType.isAssignableFrom(argTypes[i])) {
					//没有过该类型记录
					if (index == null) {
						index = i;
					} else {
						//有过，不允许
						throw new BindingException(
							method.getName() + " cannot have multiple " + paramType.getSimpleName()
								+ " parameters");
					}
				}
			}
			return index;
		}

		/**
		 * 解析方法上@MapKey的值
		 */
		private String getMapKey(Method method) {
			String mapKey = null;
			//因为@MapKey的返回值必须是一个Map类型，所以先进行判断
			if (Map.class.isAssignableFrom(method.getReturnType())) {
				final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
				if (mapKeyAnnotation != null) {
					mapKey = mapKeyAnnotation.value();
				}
			}
			return mapKey;
		}
	}

}

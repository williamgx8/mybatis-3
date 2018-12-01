/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.JapaneseDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * 类型处理器注册中心，对应关系：
 * （一）JavaType <--> TypeHandler（一） 因为TypeHandler的种类是由其泛型决定的，而泛型实际上就是JavaType
 * （原始类型及其包装类型只能算一种）
 * （多）JdbcType <--> TypeHandler（一） 比如sql.date、timestamp、datetime都可以转换成util.date
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

	//JdbcType和其对应JavaType类型处理器的映射
	private final Map<JdbcType, TypeHandler<?>> JDBC_TYPE_HANDLER_MAP = new EnumMap<>(
		JdbcType.class);
	//JavaType和多个JdbcType--TypeHandler的映射
	private final Map<Type, Map<JdbcType, TypeHandler<?>>> TYPE_HANDLER_MAP = new ConcurrentHashMap<>();
	//未知类型处理器
	private final TypeHandler<Object> UNKNOWN_TYPE_HANDLER = new UnknownTypeHandler(this);
	//TypeHandler的class类型与本身的实例映射
	private final Map<Class<?>, TypeHandler<?>> ALL_TYPE_HANDLERS_MAP = new HashMap<>();

	private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections
		.emptyMap();

	private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

	public TypeHandlerRegistry() {
		register(Boolean.class, new BooleanTypeHandler());
		register(boolean.class, new BooleanTypeHandler());
		register(JdbcType.BOOLEAN, new BooleanTypeHandler());
		register(JdbcType.BIT, new BooleanTypeHandler());

		register(Byte.class, new ByteTypeHandler());
		register(byte.class, new ByteTypeHandler());
		register(JdbcType.TINYINT, new ByteTypeHandler());

		register(Short.class, new ShortTypeHandler());
		register(short.class, new ShortTypeHandler());
		register(JdbcType.SMALLINT, new ShortTypeHandler());

		register(Integer.class, new IntegerTypeHandler());
		register(int.class, new IntegerTypeHandler());
		register(JdbcType.INTEGER, new IntegerTypeHandler());

		register(Long.class, new LongTypeHandler());
		register(long.class, new LongTypeHandler());

		register(Float.class, new FloatTypeHandler());
		register(float.class, new FloatTypeHandler());
		register(JdbcType.FLOAT, new FloatTypeHandler());

		register(Double.class, new DoubleTypeHandler());
		register(double.class, new DoubleTypeHandler());
		register(JdbcType.DOUBLE, new DoubleTypeHandler());

		register(Reader.class, new ClobReaderTypeHandler());
		register(String.class, new StringTypeHandler());
		register(String.class, JdbcType.CHAR, new StringTypeHandler());
		register(String.class, JdbcType.CLOB, new ClobTypeHandler());
		register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
		register(String.class, JdbcType.LONGVARCHAR, new ClobTypeHandler());
		register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
		register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
		register(JdbcType.CHAR, new StringTypeHandler());
		register(JdbcType.VARCHAR, new StringTypeHandler());
		register(JdbcType.CLOB, new ClobTypeHandler());
		register(JdbcType.LONGVARCHAR, new ClobTypeHandler());
		register(JdbcType.NVARCHAR, new NStringTypeHandler());
		register(JdbcType.NCHAR, new NStringTypeHandler());
		register(JdbcType.NCLOB, new NClobTypeHandler());

		register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
		register(JdbcType.ARRAY, new ArrayTypeHandler());

		register(BigInteger.class, new BigIntegerTypeHandler());
		register(JdbcType.BIGINT, new LongTypeHandler());

		register(BigDecimal.class, new BigDecimalTypeHandler());
		register(JdbcType.REAL, new BigDecimalTypeHandler());
		register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
		register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

		register(InputStream.class, new BlobInputStreamTypeHandler());
		register(Byte[].class, new ByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
		register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
		register(byte[].class, new ByteArrayTypeHandler());
		register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
		register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
		register(JdbcType.BLOB, new BlobTypeHandler());

		register(Object.class, UNKNOWN_TYPE_HANDLER);
		register(Object.class, JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);
		register(JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);

		register(Date.class, new DateTypeHandler());
		register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
		register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
		register(JdbcType.TIMESTAMP, new DateTypeHandler());
		register(JdbcType.DATE, new DateOnlyTypeHandler());
		register(JdbcType.TIME, new TimeOnlyTypeHandler());

		register(java.sql.Date.class, new SqlDateTypeHandler());
		register(java.sql.Time.class, new SqlTimeTypeHandler());
		register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

		register(String.class, JdbcType.SQLXML, new SqlxmlTypeHandler());

		register(Instant.class, InstantTypeHandler.class);
		register(LocalDateTime.class, LocalDateTimeTypeHandler.class);
		register(LocalDate.class, LocalDateTypeHandler.class);
		register(LocalTime.class, LocalTimeTypeHandler.class);
		register(OffsetDateTime.class, OffsetDateTimeTypeHandler.class);
		register(OffsetTime.class, OffsetTimeTypeHandler.class);
		register(ZonedDateTime.class, ZonedDateTimeTypeHandler.class);
		register(Month.class, MonthTypeHandler.class);
		register(Year.class, YearTypeHandler.class);
		register(YearMonth.class, YearMonthTypeHandler.class);
		register(JapaneseDate.class, JapaneseDateTypeHandler.class);

		// issue #273
		register(Character.class, new CharacterTypeHandler());
		register(char.class, new CharacterTypeHandler());
	}

	/**
	 * Set a default {@link TypeHandler} class for {@link Enum}.
	 * A default {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
	 *
	 * @param typeHandler a type handler class for {@link Enum}
	 * @since 3.4.5
	 */
	public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
		this.defaultEnumTypeHandler = typeHandler;
	}

	public boolean hasTypeHandler(Class<?> javaType) {
		return hasTypeHandler(javaType, null);
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
		return hasTypeHandler(javaTypeReference, null);
	}

	public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
		return javaType != null && getTypeHandler((Type) javaType, jdbcType) != null;
	}

	public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
		return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
	}

	public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
		return ALL_TYPE_HANDLERS_MAP.get(handlerType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
		return getTypeHandler((Type) type, null);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
		return getTypeHandler(javaTypeReference, null);
	}

	public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
		return JDBC_TYPE_HANDLER_MAP.get(jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
		return getTypeHandler((Type) type, jdbcType);
	}

	public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference,
		JdbcType jdbcType) {
		return getTypeHandler(javaTypeReference.getRawType(), jdbcType);
	}

	/**
	 * 根据JavaType和JdbcType获得对应的TypeHandler
	 *
	 * @param type Java Type
	 * @param jdbcType JdbcType
	 */
	@SuppressWarnings("unchecked")
	private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
		//忽略ParamMap类型
		if (ParamMap.class.equals(type)) {
			return null;
		}
		//获得JavaType对应 JdbcType--TypeHandler对
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);
		TypeHandler<?> handler = null;
		if (jdbcHandlerMap != null) {
			//
			handler = jdbcHandlerMap.get(jdbcType);
			if (handler == null) {
				//没有JdbcType对应TypeHandler，取默认的
				handler = jdbcHandlerMap.get(null);
			}
			if (handler == null) {
				// #591
				handler = pickSoleHandler(jdbcHandlerMap);
			}
		}
		// type drives generics here
		return (TypeHandler<T>) handler;
	}

	/**
	 * 根据JavaType得到其下一组 JdbcType--TypeHandler对
	 */
	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(type);
		//不存在返回null
		if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
			return null;
		}
		if (jdbcHandlerMap == null && type instanceof Class) {
			Class<?> clazz = (Class<?>) type;
			//如果JavaType是枚举
			if (clazz.isEnum()) {
				//获得枚举实现接口对应的 JdbcType -- TypeHandler对
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(clazz, clazz);
				if (jdbcHandlerMap == null) {
					//确实没有，注册默认的EnumTypeHandler
					register(clazz, getInstance(clazz, defaultEnumTypeHandler));
					return TYPE_HANDLER_MAP.get(clazz);
				}
			} else {
				//非枚举，就可能存在父类
				jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
			}
		}
		TYPE_HANDLER_MAP.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
		return jdbcHandlerMap;
	}

	/**
	 * 所有枚举默认继承了Enum，所以枚举只能有接口实现，没有父类
	 */
	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz,
		Class<?> enumClazz) {
		//遍历所有枚举实现的接口
		for (Class<?> iface : clazz.getInterfaces()) {
			//得到接口对应的 JdbcType--TypeHandler对
			Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(iface);
			if (jdbcHandlerMap == null) {
				//为null，再递归，接口的父接口
				jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
			}
			if (jdbcHandlerMap != null) {
				// Found a type handler regsiterd to a super interface
				/**
				 * 查找到接口的 JdbcType--TypeHandler对，而不是当前枚举类的，所以要将接口的
				 * JdbcType--TypeHandler对复制一份给枚举
				 */
				HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<>();
				for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
					// Create a type handler instance with enum type as a constructor arg
					newMap.put(entry.getKey(), getInstance(enumClazz, entry.getValue().getClass()));
				}
				return newMap;
			}
		}
		return null;
	}

	/**
	 * 获得父类Type对应的 JdbcType---TypeHandler对
	 */
	private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
		Class<?> superclass = clazz.getSuperclass();
		//没父类返回null
		if (superclass == null || Object.class.equals(superclass)) {
			return null;
		}
		Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(superclass);
		if (jdbcHandlerMap != null) {
			//存在父类对应  JdbcType--TypeHandler 返回
			return jdbcHandlerMap;
		} else {
			// 在直接父类没找到，查找再上层父类
			return getJdbcHandlerMapForSuperclass(superclass);
		}
	}

	/**
	 * 如果在给定JavaType和JdbcType的前提下没有那个唯一对应的TypeHandler，那么就选择JavaType对应的
	 * 一组 JdbcType--TypeHandler对中第一个没有被多个JdbcType共享的TypeHandler，如果再没有就返回null
	 *
	 * @param jdbcHandlerMap JdbcType--TypeHandler对
	 */
	private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
		TypeHandler<?> soleHandler = null;
		for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
			if (soleHandler == null) {
				soleHandler = handler;
			} else if (!handler.getClass().equals(soleHandler.getClass())) {
				// More than one type handlers registered.
				return null;
			}
		}
		return soleHandler;
	}

	public TypeHandler<Object> getUnknownTypeHandler() {
		return UNKNOWN_TYPE_HANDLER;
	}

	public void register(JdbcType jdbcType, TypeHandler<?> handler) {
		JDBC_TYPE_HANDLER_MAP.put(jdbcType, handler);
	}

	//
	// REGISTER INSTANCE
	//

	// Only handler

	@SuppressWarnings("unchecked")
	public <T> void register(TypeHandler<T> typeHandler) {
		boolean mappedTypeFound = false;
		//检查类上MappedTypes注解，解析其中的java type
		MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
		if (mappedTypes != null) {
			for (Class<?> handledType : mappedTypes.value()) {
				//绑定注册java type和对应的TypeHandler
				register(handledType, typeHandler);
				mappedTypeFound = true;
			}
		}
		// @since 3.1.0 - try to auto-discover the mapped type
		//如果没指定对应的java type，解析TypeHandler上泛型内的真实类型
		if (!mappedTypeFound && typeHandler instanceof TypeReference) {
			try {
				TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
				//得到java type再注册
				register(typeReference.getRawType(), typeHandler);
				mappedTypeFound = true;
			} catch (Throwable t) {
				// maybe users define the TypeReference with a different type and are not assignable, so just ignore it
			}
		}
		if (!mappedTypeFound) {
			//没有java type注册个null
			register((Class<T>) null, typeHandler);
		}
	}

	// java type + handler

	/**
	 * 注册JavaType对应的TypeHandler
	 *
	 * @param javaType java类型
	 * @param typeHandler 类型处理器
	 */
	public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
		register((Type) javaType, typeHandler);
	}

	private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
		//看看是否添加@MappedJdbcTypes
		MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass()
			.getAnnotation(MappedJdbcTypes.class);
		if (mappedJdbcTypes != null) {
			//根据解析到的JdbcType进行注册
			for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
				register(javaType, handledJdbcType, typeHandler);
			}
			if (mappedJdbcTypes.includeNullJdbcType()) {
				register(javaType, null, typeHandler);
			}
		} else {
			//没有JdbcType属性用null进行注册
			register(javaType, null, typeHandler);
		}
	}

	public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
		register(javaTypeReference.getRawType(), handler);
	}

	// java type + jdbc type + handler

	public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
		register((Type) type, jdbcType, handler);
	}

	/**
	 * 该方法中javaType可能为null，表示既没有配置xml的javaType属性，也没有@MappedType中的value值，
	 * 也无法通过解析TypeHandler的泛型获得javaType
	 */
	private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
		if (javaType != null) {
			//得到javaType对应的所有 JdbcType--TypeHandler映射
			Map<JdbcType, TypeHandler<?>> map = TYPE_HANDLER_MAP.get(javaType);
			//没有任何映射就创建一组
			if (map == null || map == NULL_TYPE_HANDLER_MAP) {
				map = new HashMap<>();
				TYPE_HANDLER_MAP.put(javaType, map);
			}
			map.put(jdbcType, handler);
		}
		//记录进TypeHandler总Map中
		ALL_TYPE_HANDLERS_MAP.put(handler.getClass(), handler);
	}

	//
	// REGISTER CLASS
	//

	// Only handler type

	public void register(Class<?> typeHandlerClass) {
		boolean mappedTypeFound = false;
		MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
		//看看是否在TypeHandler上用注解@MappedTypes指定了JavaType
		if (mappedTypes != null) {
			//遍历注解中所有的javaType，我理解除了包装类和基本类型有多个javaType，其余情况只有一个
			for (Class<?> javaTypeClass : mappedTypes.value()) {
				//注册
				register(javaTypeClass, typeHandlerClass);
				mappedTypeFound = true;
			}
		}
		if (!mappedTypeFound) {
			register(getInstance(null, typeHandlerClass));
		}
	}

	// java type + handler type

	public void register(String javaTypeClassName, String typeHandlerClassName)
		throws ClassNotFoundException {
		register(Resources.classForName(javaTypeClassName),
			Resources.classForName(typeHandlerClassName));
	}

	public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		//创建TypeHandler的实例对象并注册
		register(javaTypeClass, getInstance(javaTypeClass, typeHandlerClass));
	}

	// java type + jdbc type + handler type

	public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
		register(javaTypeClass, jdbcType, getInstance(javaTypeClass, typeHandlerClass));
	}

	// Construct a handler (used also from Builders)

	/**
	 * 根据java类型和TypeHandler类型创建类型处理器
	 *
	 * @param javaTypeClass java类型
	 * @param typeHandlerClass 类型处理器类型
	 */
	@SuppressWarnings("unchecked")
	public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
		//java类型不为空，创建指定类型的TypeHandler，比如EnumTypeHandler
		if (javaTypeClass != null) {
			try {
				Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
				return (TypeHandler<T>) c.newInstance(javaTypeClass);
			} catch (NoSuchMethodException ignored) {
				// ignored
			} catch (Exception e) {
				throw new TypeException(
					"Failed invoking constructor for handler " + typeHandlerClass, e);
			}
		}
		try {
			//直接创建指定TypeHandler实例，比如IntegerTypeHandler
			Constructor<?> c = typeHandlerClass.getConstructor();
			return (TypeHandler<T>) c.newInstance();
		} catch (Exception e) {
			throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass,
				e);
		}
	}

	// scan

	/**
	 * 扫描指定包下所有的TypeHandler
	 * 这种包扫描的套路都差不多：
	 * 1.递归根路径下所有文件，得到全路径的文件名列表；
	 * 2.把文件名的/换成.，进行一些匹配校验
	 * 3.通过反射把全路径变成Class
	 */
	public void register(String packageName) {
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		//开始扫
		resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
		//得到扫出来并且转换好的Class集合
		Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
		//遍历判断进行注册
		for (Class<?> type : handlerSet) {
			//Ignore inner classes and interfaces (including package-info.java) and abstract classes
			if (!type.isAnonymousClass() && !type.isInterface() && !Modifier
				.isAbstract(type.getModifiers())) {
				register(type);
			}
		}
	}

	// get information

	/**
	 * @since 3.2.2
	 */
	public Collection<TypeHandler<?>> getTypeHandlers() {
		return Collections.unmodifiableCollection(ALL_TYPE_HANDLERS_MAP.values());
	}

}

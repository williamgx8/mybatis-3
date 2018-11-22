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
package org.apache.ibatis.builder.annotation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.Case;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.annotations.Property;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.TypeDiscriminator;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * Mapper映射相关注解解析器，Mapper接口可以没有Mapper.xml配置文件，
 * 而是直接在接口上使用@Select、@Insert等注解完成<select/>相关语句
 * 标签的功能，该类就是用来解析和处理语句注解的
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

	private static final Set<Class<? extends Annotation>> SQL_ANNOTATION_TYPES = new HashSet<>();
	private static final Set<Class<? extends Annotation>> SQL_PROVIDER_ANNOTATION_TYPES = new HashSet<>();

	private final Configuration configuration;
	private final MapperBuilderAssistant assistant;
	private final Class<?> type;

	static {
		//初始化记录所有注解
		SQL_ANNOTATION_TYPES.add(Select.class);
		SQL_ANNOTATION_TYPES.add(Insert.class);
		SQL_ANNOTATION_TYPES.add(Update.class);
		SQL_ANNOTATION_TYPES.add(Delete.class);

		//动态SQL相关注解
		SQL_PROVIDER_ANNOTATION_TYPES.add(SelectProvider.class);
		SQL_PROVIDER_ANNOTATION_TYPES.add(InsertProvider.class);
		SQL_PROVIDER_ANNOTATION_TYPES.add(UpdateProvider.class);
		SQL_PROVIDER_ANNOTATION_TYPES.add(DeleteProvider.class);
	}

	public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
		String resource = type.getName().replace('.', '/') + ".java (best guess)";
		this.assistant = new MapperBuilderAssistant(configuration, resource);
		this.configuration = configuration;
		this.type = type;
	}

	public void parse() {
		//Mapper接口Class名称
		String resource = type.toString();
		//没有加载过Mapper，说明存在接口中存在语句注解
		if (!configuration.isResourceLoaded(resource)) {
			//再尝试加载Mapper.xml
			loadXmlResource();
			configuration.addLoadedResource(resource);
			assistant.setCurrentNamespace(type.getName());
			//解析@CacheNamespace
			parseCache();
			//解析@CacheNamespaceRef
			parseCacheRef();
			//Mapper接口中所有的方法
			Method[] methods = type.getMethods();
			for (Method method : methods) {
				try {
					// issue #237
					//非桥接方法，桥接方法是为了解决泛型引入后，类型擦除问题的额外生成的方法
					if (!method.isBridge()) {
						//解析语句方法
						parseStatement(method);
					}
				} catch (IncompleteElementException e) {
					configuration.addIncompleteMethod(new MethodResolver(this, method));
				}
			}
		}
		parsePendingMethods();
	}

	private void parsePendingMethods() {
		Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
		synchronized (incompleteMethods) {
			Iterator<MethodResolver> iter = incompleteMethods.iterator();
			while (iter.hasNext()) {
				try {
					iter.next().resolve();
					iter.remove();
				} catch (IncompleteElementException e) {
					// This method is still missing a resource
				}
			}
		}
	}

	/**
	 * 加载并解析Mapper.xml
	 */
	private void loadXmlResource() {
		// Spring may not know the real resource name so we check a flag
		// to prevent loading again a resource twice
		// this flag is set at XMLMapperBuilder#bindMapperForNamespace
		//检查loadedReource是否存在待解析的Mapper.xml
		if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
			//将类路径转换成文件路径
			String xmlResource = type.getName().replace('.', '/') + ".xml";
			// #1347
			//获取流
			InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
			if (inputStream == null) {
				// Search XML mapper that is not in the module but in the classpath.
				try {
					//文件路径不存在，类加载器直接加载类的流对象
					inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
				} catch (IOException e2) {
					// ignore, resource is not required
				}
			}
			if (inputStream != null) {
				//得到Mapper.xml对应流对象，构建Mapper解析器进行解析
				XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream,
					assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(),
					type.getName());
				//解析
				xmlParser.parse();
			}
		}
	}

	/**
	 * @CacheNamespace  用于缓存的配置
	 */
	private void parseCache() {
		CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
		if (cacheDomain != null) {
			//处理缓存大小、刷新间隔等等属性
			Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
			Long flushInterval =
				cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
			//转换所有@Property注解配置的属性
			Properties props = convertToProperties(cacheDomain.properties());
			//创建缓存
			assistant
				.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval,
					size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
		}
	}

	private Properties convertToProperties(Property[] properties) {
		if (properties.length == 0) {
			return null;
		}
		Properties props = new Properties();
		for (Property property : properties) {
			props.setProperty(property.name(),
				PropertyParser.parse(property.value(), configuration.getVariables()));
		}
		return props;
	}

	/**
	 * @CacheNamespaceRef   缓存引用注解
	 */
	private void parseCacheRef() {
		CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
		if (cacheDomainRef != null) {
			//value属性对应缓存引用的java类型
			Class<?> refType = cacheDomainRef.value();
			//name属性对应缓存引用类的全路径
			String refName = cacheDomainRef.name();
			//两个属性至少存在一个
			if (refType == void.class && refName.isEmpty()) {
				throw new BuilderException(
					"Should be specified either value() or name() attribute in the @CacheNamespaceRef");
			}
			//两个属性只能存在一个
			if (refType != void.class && !refName.isEmpty()) {
				throw new BuilderException(
					"Cannot use both value() and name() attribute in the @CacheNamespaceRef");
			}
			//类名和全路径名选一个
			String namespace = (refType != void.class) ? refType.getName() : refName;
			//获得缓存引用
			assistant.useCacheRef(namespace);
		}
	}

	private String parseResultMap(Method method) {
		Class<?> returnType = getReturnType(method);
		ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
		Results results = method.getAnnotation(Results.class);
		TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
		String resultMapId = generateResultMapName(method);
		applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results),
			typeDiscriminator);
		return resultMapId;
	}

	private String generateResultMapName(Method method) {
		Results results = method.getAnnotation(Results.class);
		if (results != null && !results.id().isEmpty()) {
			return type.getName() + "." + results.id();
		}
		StringBuilder suffix = new StringBuilder();
		for (Class<?> c : method.getParameterTypes()) {
			suffix.append("-");
			suffix.append(c.getSimpleName());
		}
		if (suffix.length() < 1) {
			suffix.append("-void");
		}
		return type.getName() + "." + method.getName() + suffix;
	}

	private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args,
		Result[] results, TypeDiscriminator discriminator) {
		List<ResultMapping> resultMappings = new ArrayList<>();
		applyConstructorArgs(args, returnType, resultMappings);
		applyResults(results, returnType, resultMappings);
		Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
		// TODO add AutoMappingBehaviour
		assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
		createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
	}

	private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType,
		TypeDiscriminator discriminator) {
		if (discriminator != null) {
			for (Case c : discriminator.cases()) {
				String caseResultMapId = resultMapId + "-" + c.value();
				List<ResultMapping> resultMappings = new ArrayList<>();
				// issue #136
				applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
				applyResults(c.results(), resultType, resultMappings);
				// TODO add AutoMappingBehaviour
				assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings,
					null);
			}
		}
	}

	private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType,
		TypeDiscriminator discriminator) {
		if (discriminator != null) {
			String column = discriminator.column();
			Class<?> javaType =
				discriminator.javaType() == void.class ? String.class : discriminator.javaType();
			JdbcType jdbcType =
				discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
			@SuppressWarnings("unchecked")
			Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
				(discriminator.typeHandler() == UnknownTypeHandler.class ? null
					: discriminator.typeHandler());
			Case[] cases = discriminator.cases();
			Map<String, String> discriminatorMap = new HashMap<>();
			for (Case c : cases) {
				String value = c.value();
				String caseResultMapId = resultMapId + "-" + value;
				discriminatorMap.put(value, caseResultMapId);
			}
			return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler,
				discriminatorMap);
		}
		return null;
	}

	/**
	 * 解析被各种注解修饰的接口方法语句
	 * @param method 接口方法
	 */
	void parseStatement(Method method) {
		//参数类型
		Class<?> parameterTypeClass = getParameterType(method);
		//语言驱动
		LanguageDriver languageDriver = getLanguageDriver(method);
		SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass,
			languageDriver);
		if (sqlSource != null) {
			Options options = method.getAnnotation(Options.class);
			final String mappedStatementId = type.getName() + "." + method.getName();
			Integer fetchSize = null;
			Integer timeout = null;
			StatementType statementType = StatementType.PREPARED;
			ResultSetType resultSetType = null;
			SqlCommandType sqlCommandType = getSqlCommandType(method);
			boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
			boolean flushCache = !isSelect;
			boolean useCache = isSelect;

			KeyGenerator keyGenerator;
			String keyProperty = null;
			String keyColumn = null;
			if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE
				.equals(sqlCommandType)) {
				// first check for SelectKey annotation - that overrides everything else
				SelectKey selectKey = method.getAnnotation(SelectKey.class);
				if (selectKey != null) {
					keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId,
						getParameterType(method), languageDriver);
					keyProperty = selectKey.keyProperty();
				} else if (options == null) {
					keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE
						: NoKeyGenerator.INSTANCE;
				} else {
					keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE
						: NoKeyGenerator.INSTANCE;
					keyProperty = options.keyProperty();
					keyColumn = options.keyColumn();
				}
			} else {
				keyGenerator = NoKeyGenerator.INSTANCE;
			}

			if (options != null) {
				if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
					flushCache = true;
				} else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
					flushCache = false;
				}
				useCache = options.useCache();
				fetchSize =
					options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options
						.fetchSize() : null; //issue #348
				timeout = options.timeout() > -1 ? options.timeout() : null;
				statementType = options.statementType();
				resultSetType = options.resultSetType();
			}

			String resultMapId = null;
			ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
			if (resultMapAnnotation != null) {
				String[] resultMaps = resultMapAnnotation.value();
				StringBuilder sb = new StringBuilder();
				for (String resultMap : resultMaps) {
					if (sb.length() > 0) {
						sb.append(",");
					}
					sb.append(resultMap);
				}
				resultMapId = sb.toString();
			} else if (isSelect) {
				resultMapId = parseResultMap(method);
			}

			assistant.addMappedStatement(
				mappedStatementId,
				sqlSource,
				statementType,
				sqlCommandType,
				fetchSize,
				timeout,
				// ParameterMapID
				null,
				parameterTypeClass,
				resultMapId,
				getReturnType(method),
				resultSetType,
				flushCache,
				useCache,
				// TODO gcode issue #577
				false,
				keyGenerator,
				keyProperty,
				keyColumn,
				// DatabaseID
				null,
				languageDriver,
				// ResultSets
				options != null ? nullOrEmpty(options.resultSets()) : null);
		}
	}

	/**
	 * 语言驱动
	 * @param method 语句接口
	 * @return
	 */
	private LanguageDriver getLanguageDriver(Method method) {
		//获取@Lang注解内的驱动Class
		Lang lang = method.getAnnotation(Lang.class);
		Class<? extends LanguageDriver> langClass = null;
		if (lang != null) {
			langClass = lang.value();
		}
		//获得对应驱动
		return assistant.getLanguageDriver(langClass);
	}

	/**
	 * 获得参数类型
	 * @param method 语句接口
	 * @return
	 */
	private Class<?> getParameterType(Method method) {
		Class<?> parameterType = null;
		Class<?>[] parameterTypes = method.getParameterTypes();
		for (Class<?> currentParameterType : parameterTypes) {
			//遍历参数列表，排除泛型T extends这种和ResultHandler类型
			if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class
				.isAssignableFrom(currentParameterType)) {
				//第一个/唯一一个参数，那么类型就是它
				if (parameterType == null) {
					parameterType = currentParameterType;
				} else {
					// issue #135
					//如果出现第二个参数了就是ParamMap类型
					parameterType = ParamMap.class;
				}
			}
		}
		return parameterType;
	}

	private Class<?> getReturnType(Method method) {
		Class<?> returnType = method.getReturnType();
		Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
		if (resolvedReturnType instanceof Class) {
			returnType = (Class<?>) resolvedReturnType;
			if (returnType.isArray()) {
				returnType = returnType.getComponentType();
			}
			// gcode issue #508
			if (void.class.equals(returnType)) {
				ResultType rt = method.getAnnotation(ResultType.class);
				if (rt != null) {
					returnType = rt.value();
				}
			}
		} else if (resolvedReturnType instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
			Class<?> rawType = (Class<?>) parameterizedType.getRawType();
			if (Collection.class.isAssignableFrom(rawType) || Cursor.class
				.isAssignableFrom(rawType)) {
				Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
				if (actualTypeArguments != null && actualTypeArguments.length == 1) {
					Type returnTypeParameter = actualTypeArguments[0];
					if (returnTypeParameter instanceof Class<?>) {
						returnType = (Class<?>) returnTypeParameter;
					} else if (returnTypeParameter instanceof ParameterizedType) {
						// (gcode issue #443) actual type can be a also a parameterized type
						returnType = (Class<?>) ((ParameterizedType) returnTypeParameter)
							.getRawType();
					} else if (returnTypeParameter instanceof GenericArrayType) {
						Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter)
							.getGenericComponentType();
						// (gcode issue #525) support List<byte[]>
						returnType = Array.newInstance(componentType, 0).getClass();
					}
				}
			} else if (method.isAnnotationPresent(MapKey.class) && Map.class
				.isAssignableFrom(rawType)) {
				// (gcode issue 504) Do not look into Maps if there is not MapKey annotation
				Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
				if (actualTypeArguments != null && actualTypeArguments.length == 2) {
					Type returnTypeParameter = actualTypeArguments[1];
					if (returnTypeParameter instanceof Class<?>) {
						returnType = (Class<?>) returnTypeParameter;
					} else if (returnTypeParameter instanceof ParameterizedType) {
						// (gcode issue 443) actual type can be a also a parameterized type
						returnType = (Class<?>) ((ParameterizedType) returnTypeParameter)
							.getRawType();
					}
				}
			} else if (Optional.class.equals(rawType)) {
				Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
				Type returnTypeParameter = actualTypeArguments[0];
				if (returnTypeParameter instanceof Class<?>) {
					returnType = (Class<?>) returnTypeParameter;
				}
			}
		}

		return returnType;
	}

	private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType,
		LanguageDriver languageDriver) {
		try {
			//判断得到的语句注解类型，是@Select、@Update还是其他的
			Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
			//动态SQL的@Provider类型
			Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(
				method);
			//两种必有一个，要不然你想干嘛
			if (sqlAnnotationType != null) {
				if (sqlProviderAnnotationType != null) {
					throw new BindingException(
						"You cannot supply both a static SQL and SqlProvider to method named "
							+ method.getName());
				}
				Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
				//如果存在多个字符串语句的拼接，得到这个语句集合
				final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value")
					.invoke(sqlAnnotation);
				return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
			} else if (sqlProviderAnnotationType != null) {
				Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
				return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation,
					type, method);
			}
			return null;
		} catch (Exception e) {
			throw new BuilderException(
				"Could not find value method on SQL annotation.  Cause: " + e, e);
		}
	}

	private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass,
		LanguageDriver languageDriver) {
		final StringBuilder sql = new StringBuilder();
		for (String fragment : strings) {
			sql.append(fragment);
			sql.append(" ");
		}
		return languageDriver
			.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
	}

	private SqlCommandType getSqlCommandType(Method method) {
		Class<? extends Annotation> type = getSqlAnnotationType(method);

		if (type == null) {
			type = getSqlProviderAnnotationType(method);

			if (type == null) {
				return SqlCommandType.UNKNOWN;
			}

			if (type == SelectProvider.class) {
				type = Select.class;
			} else if (type == InsertProvider.class) {
				type = Insert.class;
			} else if (type == UpdateProvider.class) {
				type = Update.class;
			} else if (type == DeleteProvider.class) {
				type = Delete.class;
			}
		}

		return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
	}

	private Class<? extends Annotation> getSqlAnnotationType(Method method) {
		return chooseAnnotationType(method, SQL_ANNOTATION_TYPES);
	}

	private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
		return chooseAnnotationType(method, SQL_PROVIDER_ANNOTATION_TYPES);
	}

	private Class<? extends Annotation> chooseAnnotationType(Method method,
		Set<Class<? extends Annotation>> types) {
		for (Class<? extends Annotation> type : types) {
			Annotation annotation = method.getAnnotation(type);
			if (annotation != null) {
				return type;
			}
		}
		return null;
	}

	private void applyResults(Result[] results, Class<?> resultType,
		List<ResultMapping> resultMappings) {
		for (Result result : results) {
			List<ResultFlag> flags = new ArrayList<>();
			if (result.id()) {
				flags.add(ResultFlag.ID);
			}
			@SuppressWarnings("unchecked")
			Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
				((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
			ResultMapping resultMapping = assistant.buildResultMapping(
				resultType,
				nullOrEmpty(result.property()),
				nullOrEmpty(result.column()),
				result.javaType() == void.class ? null : result.javaType(),
				result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
				hasNestedSelect(result) ? nestedSelectId(result) : null,
				null,
				null,
				null,
				typeHandler,
				flags,
				null,
				null,
				isLazy(result));
			resultMappings.add(resultMapping);
		}
	}

	private String nestedSelectId(Result result) {
		String nestedSelect = result.one().select();
		if (nestedSelect.length() < 1) {
			nestedSelect = result.many().select();
		}
		if (!nestedSelect.contains(".")) {
			nestedSelect = type.getName() + "." + nestedSelect;
		}
		return nestedSelect;
	}

	private boolean isLazy(Result result) {
		boolean isLazy = configuration.isLazyLoadingEnabled();
		if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
			isLazy = result.one().fetchType() == FetchType.LAZY;
		} else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many()
			.fetchType()) {
			isLazy = result.many().fetchType() == FetchType.LAZY;
		}
		return isLazy;
	}

	private boolean hasNestedSelect(Result result) {
		if (result.one().select().length() > 0 && result.many().select().length() > 0) {
			throw new BuilderException(
				"Cannot use both @One and @Many annotations in the same @Result");
		}
		return result.one().select().length() > 0 || result.many().select().length() > 0;
	}

	private void applyConstructorArgs(Arg[] args, Class<?> resultType,
		List<ResultMapping> resultMappings) {
		for (Arg arg : args) {
			List<ResultFlag> flags = new ArrayList<>();
			flags.add(ResultFlag.CONSTRUCTOR);
			if (arg.id()) {
				flags.add(ResultFlag.ID);
			}
			@SuppressWarnings("unchecked")
			Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
				(arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
			ResultMapping resultMapping = assistant.buildResultMapping(
				resultType,
				nullOrEmpty(arg.name()),
				nullOrEmpty(arg.column()),
				arg.javaType() == void.class ? null : arg.javaType(),
				arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
				nullOrEmpty(arg.select()),
				nullOrEmpty(arg.resultMap()),
				null,
				nullOrEmpty(arg.columnPrefix()),
				typeHandler,
				flags,
				null,
				null,
				false);
			resultMappings.add(resultMapping);
		}
	}

	private String nullOrEmpty(String value) {
		return value == null || value.trim().length() == 0 ? null : value;
	}

	private Result[] resultsIf(Results results) {
		return results == null ? new Result[0] : results.value();
	}

	private Arg[] argsIf(ConstructorArgs args) {
		return args == null ? new Arg[0] : args.value();
	}

	private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation,
		String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
		String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
		Class<?> resultTypeClass = selectKeyAnnotation.resultType();
		StatementType statementType = selectKeyAnnotation.statementType();
		String keyProperty = selectKeyAnnotation.keyProperty();
		String keyColumn = selectKeyAnnotation.keyColumn();
		boolean executeBefore = selectKeyAnnotation.before();

		// defaults
		boolean useCache = false;
		KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
		Integer fetchSize = null;
		Integer timeout = null;
		boolean flushCache = false;
		String parameterMap = null;
		String resultMap = null;
		ResultSetType resultSetTypeEnum = null;

		SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(),
			parameterTypeClass, languageDriver);
		SqlCommandType sqlCommandType = SqlCommandType.SELECT;

		assistant
			.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
				parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
				flushCache, useCache, false,
				keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

		id = assistant.applyCurrentNamespace(id, false);

		MappedStatement keyStatement = configuration.getMappedStatement(id, false);
		SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
		configuration.addKeyGenerator(id, answer);
		return answer;
	}

}

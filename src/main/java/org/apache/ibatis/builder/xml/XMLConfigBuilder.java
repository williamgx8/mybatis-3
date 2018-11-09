/**
 * Copyright 2009-2017 the original author or authors.
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
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

	//是否已解析
	private boolean parsed;
	//xpath解析器对象
	private final XPathParser parser;
	private String environment;
	//用于获取类Reflector的工厂
	private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

	public XMLConfigBuilder(Reader reader) {
		this(reader, null, null);
	}

	public XMLConfigBuilder(Reader reader, String environment) {
		this(reader, environment, null);
	}

	public XMLConfigBuilder(Reader reader, String environment, Properties props) {
		this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment,
			props);
	}

	public XMLConfigBuilder(InputStream inputStream) {
		this(inputStream, null, null);
	}

	public XMLConfigBuilder(InputStream inputStream, String environment) {
		this(inputStream, environment, null);
	}

	public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
		this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment,
			props);
	}

	private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
		//父类BaseBuilder创建一些基础的处理器
		super(new Configuration());
		//创建当前会话的错误上下文对象
		ErrorContext.instance().resource("SQL Mapper Configuration");
		this.configuration.setVariables(props);
		this.parsed = false;
		this.environment = environment;
		this.parser = parser;
	}

	/**
	 * 解析Mybatis-Config.xml入口
	 */
	public Configuration parse() {
		if (parsed) {
			throw new BuilderException("Each XMLConfigBuilder can only be used once.");
		}
		parsed = true;
		//因为Mybatis-Config.xml的根节点为<configuration>，先解析根节点，再解析xml中一个个元素
		parseConfiguration(parser.evalNode("/configuration"));
		return configuration;
	}

	private void parseConfiguration(XNode root) {
		try {
			//issue #117 read properties first
			//<properties></properties>
			propertiesElement(root.evalNode("properties"));
			//<settings></settings>
			Properties settings = settingsAsProperties(root.evalNode("settings"));
			//解析自定义VFS
			loadCustomVfs(settings);
			//<typeAlias></typeAlias>类型昵称
			typeAliasesElement(root.evalNode("typeAliases"));
			//<plugins></plugins>
			pluginElement(root.evalNode("plugins"));
			//自定义对象工厂
			objectFactoryElement(root.evalNode("objectFactory"));
			//对象工厂包装对象
			objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
			//反射器工厂
			reflectorFactoryElement(root.evalNode("reflectorFactory"));
			//将上面解析的<setting>标签中属性，设置到Configuration对应属性中
			settingsElement(settings);
			// read it after objectFactory and objectWrapperFactory issue #631
			//<environments></environments>
			environmentsElement(root.evalNode("environments"));
			//支持不同数据库厂商的不同语句，需要配置<databaseIdProvider/>
			databaseIdProviderElement(root.evalNode("databaseIdProvider"));
			//处理自定义<typeHandlers/>
			typeHandlerElement(root.evalNode("typeHandlers"));
			//解析映射器
			mapperElement(root.evalNode("mappers"));
		} catch (Exception e) {
			throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
		}
	}

	/**
	 * 解析
	 * <settings>
	 * <setting name="xxx" value="xxx"/>
	 * <setting name="xxx" value="xxx"/>
	 * </settings>
	 */
	private Properties settingsAsProperties(XNode context) {
		if (context == null) {
			return new Properties();
		}
		//解析<setting name="xxx" value="xxx"/>
		Properties props = context.getChildrenAsProperties();
		// Check that all settings are known to the configuration class
		// 解析Configuration，生成对应元类MetaClass
		MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
		for (Object key : props.keySet()) {
			//<settings/>中所有配置的属性必须是系统属性，即在Configuration中存在
			if (!metaConfig.hasSetter(String.valueOf(key))) {
				throw new BuilderException("The setting " + key
					+ " is not known.  Make sure you spelled it correctly (case sensitive).");
			}
		}
		return props;
	}

	/**
	 * 解析并设置自定义VFS
	 */
	private void loadCustomVfs(Properties props) throws ClassNotFoundException {
		//自定义VFS对应<setting>的vfsImpl属性
		String value = props.getProperty("vfsImpl");
		if (value != null) {
			//多个自定义VFS以逗号分隔
			String[] clazzes = value.split(",");
			for (String clazz : clazzes) {
				if (!clazz.isEmpty()) {
					@SuppressWarnings("unchecked")
					//加载全路径名对应Class
						Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources
						.classForName(clazz);
					configuration.setVfsImpl(vfsImpl);
				}
			}
		}
	}

	/**
	 * 举例：
	 * <typeAliases>
	 * <typeAlias alias="Author" type="domain.blog.Author"/>
	 * <typeAlias alias="Blog" type="domain.blog.Blog"/>
	 * <typeAlias alias="Comment" type="domain.blog.Comment"/>
	 * <typeAlias alias="Post" type="domain.blog.Post"/>
	 * <typeAlias alias="Section" type="domain.blog.Section"/>
	 * <typeAlias alias="Tag" type="domain.blog.Tag"/>
	 * </typeAliases>
	 * 以及
	 * <typeAliases>
	 * <package name="domain.blog"/>
	 * </typeAliases>
	 * 两种形式
	 */
	private void typeAliasesElement(XNode parent) {
		if (parent != null) {
			for (XNode child : parent.getChildren()) {
				//存在package属性
				if ("package".equals(child.getName())) {
					//得到要扫描的包
					String typeAliasPackage = child.getStringAttribute("name");
					//注册，实际就是扫描包下所有类，用类的simple name所为昵称，class作为值进行注册
					configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
				} else {
					//一个个指定的，依次注册
					String alias = child.getStringAttribute("alias");
					String type = child.getStringAttribute("type");
					try {
						Class<?> clazz = Resources.classForName(type);
						if (alias == null) {
							typeAliasRegistry.registerAlias(clazz);
						} else {
							typeAliasRegistry.registerAlias(alias, clazz);
						}
					} catch (ClassNotFoundException e) {
						throw new BuilderException(
							"Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
					}
				}
			}
		}
	}

	/**
	 * 举例：
	 * <plugins>
	 * <plugin interceptor="org.mybatis.example.ExamplePlugin">
	 * <property name="someProperty" value="100"/>
	 * </plugin>
	 * </plugins>
	 */
	private void pluginElement(XNode parent) throws Exception {
		if (parent != null) {
			//遍历每个<plugin/>
			for (XNode child : parent.getChildren()) {
				//interceptor属性，表示拦截器类型
				String interceptor = child.getStringAttribute("interceptor");
				//还可以给拦截器带点参数
				Properties properties = child.getChildrenAsProperties();
				//创建拦截器实例
				Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor)
					.newInstance();
				//塞入所带参数
				interceptorInstance.setProperties(properties);
				//放入拦截器链中
				configuration.addInterceptor(interceptorInstance);
			}
		}
	}

	/**
	 * 举例：
	 * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
	 * <property name="someProperty" value="100"/>
	 * </objectFactory>
	 */
	private void objectFactoryElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			Properties properties = context.getChildrenAsProperties();
			ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
			factory.setProperties(properties);
			configuration.setObjectFactory(factory);
		}
	}

	private void objectWrapperFactoryElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
			configuration.setObjectWrapperFactory(factory);
		}
	}

	private void reflectorFactoryElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
			configuration.setReflectorFactory(factory);
		}
	}

	/**
	 * 举例:
	 * resource/url对应文件中有一堆key-value，<property></property>也可以配置一份
	 * <p>
	 * <properties resource="org/mybatis/example/config.properties">
	 * <property name="username" value="dev_user"/>
	 * <property name="password" value="F2Fa3!33TYyg"/>
	 * </properties>
	 * </p>
	 */
	private void propertiesElement(XNode context) throws Exception {
		if (context != null) {
			//解析<properties>下<property>的name-value
			Properties defaults = context.getChildrenAsProperties();
			//resource属性
			String resource = context.getStringAttribute("resource");
			//url属性
			String url = context.getStringAttribute("url");
			//resource和url只能有一个
			if (resource != null && url != null) {
				throw new BuilderException(
					"The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
			}
			//解析resource文件中所有键值
			if (resource != null) {
				defaults.putAll(Resources.getResourceAsProperties(resource));
				//解析url文件中键值
			} else if (url != null) {
				defaults.putAll(Resources.getUrlAsProperties(url));
			}
			//取出一开始创建Configuration时放进去的
			Properties vars = configuration.getVariables();
			if (vars != null) {
				//合在一起
				defaults.putAll(vars);
			}
			//回塞入parser和configuration中
			parser.setVariables(defaults);
			configuration.setVariables(defaults);
		}
	}

	/**
	 * 设置Mybatis-Config.xml中<setting></setting>对应配置到Configuration类中属性
	 */
	private void settingsElement(Properties props) throws Exception {
		configuration.setAutoMappingBehavior(
			AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
		configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior
			.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
		configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
		configuration
			.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
		configuration
			.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
		configuration.setAggressiveLazyLoading(
			booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
		configuration.setMultipleResultSetsEnabled(
			booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
		configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
		configuration
			.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
		configuration.setDefaultExecutorType(
			ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
		configuration.setDefaultStatementTimeout(
			integerValueOf(props.getProperty("defaultStatementTimeout"), null));
		configuration
			.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
		configuration.setMapUnderscoreToCamelCase(
			booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
		configuration.setSafeRowBoundsEnabled(
			booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
		configuration.setLocalCacheScope(
			LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
		configuration
			.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
		configuration.setLazyLoadTriggerMethods(
			stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"),
				"equals,clone,hashCode,toString"));
		configuration.setSafeResultHandlerEnabled(
			booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
		configuration.setDefaultScriptingLanguage(
			resolveClass(props.getProperty("defaultScriptingLanguage")));
		@SuppressWarnings("unchecked")
		Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(
			props.getProperty("defaultEnumTypeHandler"));
		configuration.setDefaultEnumTypeHandler(typeHandler);
		configuration
			.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
		configuration
			.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
		configuration.setReturnInstanceForEmptyRow(
			booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
		configuration.setLogPrefix(props.getProperty("logPrefix"));
		@SuppressWarnings("unchecked")
		Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(
			props.getProperty("logImpl"));
		configuration.setLogImpl(logImpl);
		configuration
			.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
	}

	/**
	 * 举例：
	 * <environments default="development">
	 * <environment id="development">
	 * <transactionManager type="JDBC">
	 * <property name="..." value="..."/>
	 * </transactionManager>
	 * <dataSource type="POOLED">
	 * <property name="driver" value="${driver}"/>
	 * <property name="url" value="${url}"/>
	 * <property name="username" value="${username}"/>
	 * <property name="password" value="${password}"/>
	 * </dataSource>
	 * </environment>
	 * </environments>
	 */
	private void environmentsElement(XNode context) throws Exception {
		if (context != null) {
			//先设置一个默认环境
			if (environment == null) {
				environment = context.getStringAttribute("default");
			}
			//遍历每个<environment/>
			for (XNode child : context.getChildren()) {
				//环境标识
				String id = child.getStringAttribute("id");
				//和成员变量environment一致为true
				if (isSpecifiedEnvironment(id)) {
					//解析并返回事务工厂
					TransactionFactory txFactory = transactionManagerElement(
						child.evalNode("transactionManager"));
					//解析dataSource相关标签，返回数据源工厂
					DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
					DataSource dataSource = dsFactory.getDataSource();
					//获得联系当前环境、transactionFactory、datasource的环境构建器
					Environment.Builder environmentBuilder = new Environment.Builder(id)
						.transactionFactory(txFactory)
						.dataSource(dataSource);
					//设置环境
					configuration.setEnvironment(environmentBuilder.build());
				}
			}
		}
	}

	/**
	 * 举例：
	 * <databaseIdProvider type="DB_VENDOR">
	 *   <property name="SQL Server" value="sqlserver"/>
	 *   <property name="DB2" value="db2"/>
	 *   <property name="Oracle" value="oracle" />
	 * </databaseIdProvider>
	 * @param context
	 * @throws Exception
	 */
	private void databaseIdProviderElement(XNode context) throws Exception {
		DatabaseIdProvider databaseIdProvider = null;
		if (context != null) {
			String type = context.getStringAttribute("type");
			// awful patch to keep backward compatibility
			//两种名称是同一个
			if ("VENDOR".equals(type)) {
				type = "DB_VENDOR";
			}
			Properties properties = context.getChildrenAsProperties();
			//创建对应provider实例
			databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
			//设置属性
			databaseIdProvider.setProperties(properties);
		}
		Environment environment = configuration.getEnvironment();
		//设置数据库提供商
		if (environment != null && databaseIdProvider != null) {
			String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
			configuration.setDatabaseId(databaseId);
		}
	}

	/**
	 * 解析<transactionmanager></transactionmanager>创建事务工厂
	 *
	 * @return 事务工厂  TransactionFactory
	 */
	private TransactionFactory transactionManagerElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			Properties props = context.getChildrenAsProperties();
			//type比如JDBC，解析JDBC对应的类，该类肯定是一个TransactionFactory
			TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
			//设置配置文件中带的属性
			factory.setProperties(props);
			return factory;
		}
		throw new BuilderException("Environment declaration requires a TransactionFactory.");
	}

	/**
	 * 解析创建数据源并设置相关属性
	 */
	private DataSourceFactory dataSourceElement(XNode context) throws Exception {
		if (context != null) {
			String type = context.getStringAttribute("type");
			Properties props = context.getChildrenAsProperties();
			DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
			factory.setProperties(props);
			return factory;
		}
		throw new BuilderException("Environment declaration requires a DataSourceFactory.");
	}

	/**
	 * 举例，存在两种模式：
	 * <databaseIdProvider type="DB_VENDOR">
	 *   <property name="SQL Server" value="sqlserver"/>
	 *   <property name="DB2" value="db2"/>
	 *   <property name="Oracle" value="oracle" />
	 * </databaseIdProvider>
	 * 和
	 * <typeHandlers>
	 *   <package name="org.mybatis.example"/>
	 * </typeHandlers>
	 * @param parent
	 * @throws Exception
	 */
	private void typeHandlerElement(XNode parent) throws Exception {
		if (parent != null) {
			for (XNode child : parent.getChildren()) {
				//存在package属性，扫描包下所有类，并注册所有类型处理器
				if ("package".equals(child.getName())) {
					String typeHandlerPackage = child.getStringAttribute("name");
					typeHandlerRegistry.register(typeHandlerPackage);
				} else {
					//一个一个解析注册
					String javaTypeName = child.getStringAttribute("javaType");
					String jdbcTypeName = child.getStringAttribute("jdbcType");
					String handlerTypeName = child.getStringAttribute("handler");
					Class<?> javaTypeClass = resolveClass(javaTypeName);
					JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
					Class<?> typeHandlerClass = resolveClass(handlerTypeName);
					if (javaTypeClass != null) {
						if (jdbcType == null) {
							typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
						} else {
							typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
						}
					} else {
						typeHandlerRegistry.register(typeHandlerClass);
					}
				}
			}
		}
	}

	/**
	 * Mapper的配置比较多样
	 *
	 * 使用相对于类路径的资源引用
	 * <mappers>
	 *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
	 *   .....
	 * </mappers>
	 * 使用完全限定资源定位符（URL）
	 * <mappers>
	 *   <mapper url="file:///var/mappers/AuthorMapper.xml"/>
	 *   ....
	 * </mappers>
	 *  使用映射器接口实现类的完全限定类名
	 * <mappers>
	 *   <mapper class="org.mybatis.builder.AuthorMapper"/>
	 *   .....
	 * </mappers>
	 * 将包内的映射器接口实现全部注册为映射器
	 * <mappers>
	 *   <package name="org.mybatis.builder"/>
	 * </mappers>
	 * @param parent
	 * @throws Exception
	 */
	private void mapperElement(XNode parent) throws Exception {
		if (parent != null) {
			for (XNode child : parent.getChildren()) {
				//套路，扫包注册
				if ("package".equals(child.getName())) {
					String mapperPackage = child.getStringAttribute("name");
					configuration.addMappers(mapperPackage);
				} else {
					String resource = child.getStringAttribute("resource");
					String url = child.getStringAttribute("url");
					String mapperClass = child.getStringAttribute("class");
					if (resource != null && url == null && mapperClass == null) {
						//用于记录异常信息的上下文
						ErrorContext.instance().resource(resource);
						//获得文件流
						InputStream inputStream = Resources.getResourceAsStream(resource);
						//创建Mapper解析器
						XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream,
							configuration, resource, configuration.getSqlFragments());
						//解析Mapper
						mapperParser.parse();
					} else if (resource == null && url != null && mapperClass == null) {
						ErrorContext.instance().resource(url);
						InputStream inputStream = Resources.getUrlAsStream(url);
						XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream,
							configuration, url, configuration.getSqlFragments());
						mapperParser.parse();
					} else if (resource == null && url == null && mapperClass != null) {
						Class<?> mapperInterface = Resources.classForName(mapperClass);
						//直接添加即可
						configuration.addMapper(mapperInterface);
					} else {
						throw new BuilderException(
							"A mapper element may only specify a url, resource or class, but not more than one.");
					}
				}
			}
		}
	}

	private boolean isSpecifiedEnvironment(String id) {
		if (environment == null) {
			throw new BuilderException("No environment specified.");
		} else if (id == null) {
			throw new BuilderException("Environment requires an id attribute.");
		} else if (environment.equals(id)) {
			return true;
		}
		return false;
	}

}

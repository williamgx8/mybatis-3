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
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * Mapper.xml中诸如<select/>、<update/>等语句的解析器
 *
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

	private final MapperBuilderAssistant builderAssistant;
	private final XNode context;
	private final String requiredDatabaseId;

	public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant,
		XNode context) {
		this(configuration, builderAssistant, context, null);
	}

	public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant,
		XNode context, String databaseId) {
		super(configuration);
		this.builderAssistant = builderAssistant;
		this.context = context;
		this.requiredDatabaseId = databaseId;
	}

	/**
	 * 解析<select/>、<update/>、<insert/>等Mapper文件中的<b>一个</b>语句标签，举例：
	 * <select id="selectPerson" parameterType="int" resultType="hashmap">
	 *    SELECT * FROM PERSON WHERE ID = #{id}
	 *  </select>
	 */
	public void parseStatementNode() {
		//id对应Mapper.java中的方法名
		String id = context.getStringAttribute("id");
		//哪种数据库
		String databaseId = context.getStringAttribute("databaseId");
		//保证语句数据库方言类型和整个Mybatis数据库类型一致
		if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
			return;
		}

		//这是尝试影响驱动程序每次批量返回的结果行数和这个设置值相等
		Integer fetchSize = context.getIntAttribute("fetchSize");
		//这个设置是在抛出异常之前，驱动程序等待数据库返回请求结果的秒数
		Integer timeout = context.getIntAttribute("timeout");
		//已过时，不做分析
		String parameterMap = context.getStringAttribute("parameterMap");
		//方法参数的别名或全路径名，如果存在多个参数，该属性需要进行封装，或者能够被自动封装
		//该参数不存在也是可以的 MyBatis 可以通过 TypeHandler 推断出具体传入语句的参数
		String parameterType = context.getStringAttribute("parameterType");
		//解析参数对应类型
		Class<?> parameterTypeClass = resolveClass(parameterType);
		//语句执行的结果封装结果集
		String resultMap = context.getStringAttribute("resultMap");
		//语句执行的结果类型  resultType和resultMap只能存在一个
		String resultType = context.getStringAttribute("resultType");
		String lang = context.getStringAttribute("lang");
		LanguageDriver langDriver = getLanguageDriver(lang);
		//resultType对应类型
		Class<?> resultTypeClass = resolveClass(resultType);
		//对应枚举ResultType，数据查询方式
		String resultSetType = context.getStringAttribute("resultSetType");
		//对应枚举StatementType     STATEMENT, PREPARED, CALLABLE
		StatementType statementType = StatementType.valueOf(
			context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
		ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
		//标签语句名称
		String nodeName = context.getNode().getNodeName();
		//标签语句对应枚举
		SqlCommandType sqlCommandType = SqlCommandType
			.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
		boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
		boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
		boolean useCache = context.getBooleanAttribute("useCache", isSelect);
		boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

		// Include Fragments before parsing
		//解析<include/>，并替换为对应的sql片段
		XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration,
			builderAssistant);
		includeParser.applyIncludes(context.getNode());

		// Parse selectKey after includes and remove them.
		//处理<selectKey/> 用于配置生成列值策略
		processSelectKeyNodes(id, parameterTypeClass, langDriver);

		// Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
		//解析SQL脚本，如果包括动态SQL相关标签，进行解析
		SqlSource sqlSource = langDriver
			.createSqlSource(configuration, context, parameterTypeClass);
		String resultSets = context.getStringAttribute("resultSets");
		String keyProperty = context.getStringAttribute("keyProperty");
		String keyColumn = context.getStringAttribute("keyColumn");
		KeyGenerator keyGenerator;
		String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
		keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
		if (configuration.hasKeyGenerator(keyStatementId)) {
			keyGenerator = configuration.getKeyGenerator(keyStatementId);
		} else {
			keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
				configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
				? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
		}

		builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
			fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
			resultSetTypeEnum, flushCache, useCache, resultOrdered,
			keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
	}

	/**
	 * 解析<selectKey/>，该标签用于生成列值
	 * <selectKey
	 *   keyProperty="id"
	 *   resultType="int"
	 *   order="BEFORE"
	 *   statementType="PREPARED">
	 * @param id 语句标签的id，不是<selectKey/>的id
	 * @param parameterTypeClass 语句标签参数对应类型
	 * @param langDriver 数据库驱动
	 */
	private void processSelectKeyNodes(String id, Class<?> parameterTypeClass,
		LanguageDriver langDriver) {
		//一个语句标签中多个<selectKey/>，为了适应不同数据库，可能一个语句标签中存在多种不同的<selectKey/>，对应多种不同的主键生成策略
		List<XNode> selectKeyNodes = context.evalNodes("selectKey");
		if (configuration.getDatabaseId() != null) {
			parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver,
				configuration.getDatabaseId());
		}
		//不存在databaseId，看起来会执行两次，实际上该方法内会判断只有完全匹配才会真正执行，因此两次方法最多生效一次
		parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
		//只有一个匹配解析完成，删除该标签语句下的所有<selectKey/>
		removeSelectKeyNodes(selectKeyNodes);
	}

	/**
	 * 解析一个语句标签中所有的<selectKey/>
	 * @param parentId 语句标签id
	 * @param list 语句标签中所有<selectKey/>节点
	 * @param parameterTypeClass 语句标签对应sql的参数类型
	 * @param langDriver
	 * @param skRequiredDatabaseId
	 */
	private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass,
		LanguageDriver langDriver, String skRequiredDatabaseId) {
		for (XNode nodeToHandle : list) {
			//遍历每一个<selectKey/>生成唯一标识  ${id}!selectKey
			String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
			//得到数据库标识
			String databaseId = nodeToHandle.getStringAttribute("databaseId");
			if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
				//数据库配置，说明该<selectKey/>可以在当前数据库下执行，解析单个<selectKey/>
				parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
			}
		}
	}

	/**
	 * 解析一个<selectKey/>
	 * @param id <selectKey/>唯一标识
	 * @param nodeToHandle <selectKey/>节点对象
	 * @param parameterTypeClass 父标签语句参数类型
	 * @param langDriver
	 * @param databaseId
	 */
	private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass,
		LanguageDriver langDriver, String databaseId) {
		//生成类型
		String resultType = nodeToHandle.getStringAttribute("resultType");
		Class<?> resultTypeClass = resolveClass(resultType);
		StatementType statementType = StatementType.valueOf(
			nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
		//selectKey 语句结果应该被设置的目标属性
		String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
		//匹配属性的返回结果集中的列名称
		String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
		//生成时机
		boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

		//defaults
		boolean useCache = false;
		boolean resultOrdered = false;
		KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
		Integer fetchSize = null;
		Integer timeout = null;
		boolean flushCache = false;
		String parameterMap = null;
		String resultMap = null;
		ResultSetType resultSetTypeEnum = null;

		SqlSource sqlSource = langDriver
			.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
		SqlCommandType sqlCommandType = SqlCommandType.SELECT;

		//将<selectKey/>封装成MappedStatement，和标签语句同级
		builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
			fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
			resultSetTypeEnum, flushCache, useCache, resultOrdered,
			keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

		//<selectKey/>唯一标识
		id = builderAssistant.applyCurrentNamespace(id, false);
		//得到封装好的MappedStatement
		MappedStatement keyStatement = configuration.getMappedStatement(id, false);
		//添加生成器
		configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
	}

	private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
		for (XNode nodeToHandle : selectKeyNodes) {
			nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
		}
	}

	/**
	 * 数据库标识和当前连接的标识是否匹配
	 * @param id 语句标签id
	 * @param databaseId 待匹配的数据库标识
	 * @param requiredDatabaseId 所需要的数据库标识
	 * @return
	 */
	private boolean databaseIdMatchesCurrent(String id, String databaseId,
		String requiredDatabaseId) {
		if (requiredDatabaseId != null) {
			//存在指定的数据库标识，就必须两者对应
			if (!requiredDatabaseId.equals(databaseId)) {
				return false;
			}
		} else {
			//没有指定所需要的数据库标识，但是存在待匹配的数据库标识，肯定无法匹配
			if (databaseId != null) {
				return false;
			}
			// skip this statement if there is a previous one with a not null databaseId
			//获取完整名称空间  currentNamespace.id
			id = builderAssistant.applyCurrentNamespace(id, false);
			//存在该名称空间对应配置
			if (this.configuration.hasStatement(id, false)) {
				//父引用
				MappedStatement previous = this.configuration
					.getMappedStatement(id, false); // issue #2
				//父引用数据库标识也必须为null
				if (previous.getDatabaseId() != null) {
					return false;
				}
			}
		}
		return true;
	}

	private LanguageDriver getLanguageDriver(String lang) {
		Class<? extends LanguageDriver> langClass = null;
		if (lang != null) {
			langClass = resolveClass(lang);
		}
		return builderAssistant.getLanguageDriver(langClass);
	}

}

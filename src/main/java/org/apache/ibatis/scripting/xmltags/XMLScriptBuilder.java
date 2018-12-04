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
package org.apache.ibatis.scripting.xmltags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 动态SQL脚本构建器，对于<select/>、<update/>等语句标签来说，有两种情况是动态的：
 * 1. sql中存在${}之类的占位符
 * 2. sql中存在<set/>、<if/>等动态sql标签
 *
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

	//sql标签节点
	private final XNode context;
	//是否动态
	private boolean isDynamic;
	//参数类型，一个参数就是参数本身，多个就是ParamMap
	private final Class<?> parameterType;
	//每个标签对应的处理器映射
	private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

	public XMLScriptBuilder(Configuration configuration, XNode context) {
		this(configuration, context, null);
	}

	public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
		super(configuration);
		this.context = context;
		this.parameterType = parameterType;
		//初始化每个子节点的处理器
		initNodeHandlerMap();
	}


	private void initNodeHandlerMap() {
		nodeHandlerMap.put("trim", new TrimHandler());
		nodeHandlerMap.put("where", new WhereHandler());
		nodeHandlerMap.put("set", new SetHandler());
		nodeHandlerMap.put("foreach", new ForEachHandler());
		nodeHandlerMap.put("if", new IfHandler());
		nodeHandlerMap.put("choose", new ChooseHandler());
		nodeHandlerMap.put("when", new IfHandler());
		nodeHandlerMap.put("otherwise", new OtherwiseHandler());
		nodeHandlerMap.put("bind", new BindHandler());
	}

	/**
	 * 解析SQL脚本，包括对于动态SQL的解析
	 */
	public SqlSource parseScriptNode() {
		//sql脚本的顶层sqlNode，其中包含了层级关系的所有SqlNode节点
		MixedSqlNode rootSqlNode = parseDynamicTags(context);
		SqlSource sqlSource = null;
		if (isDynamic) {
			//如果整个sql中任何一处出现${}或者任何的动态sql标签，比如<if/>，就创建DynamicSqlSource
			sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
		} else {
			//否则就是单纯的文本sqlSource
			sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
		}
		return sqlSource;
	}

	/**
	 * 解析动态标签和动态占位符
	 */
	protected MixedSqlNode parseDynamicTags(XNode node) {
		List<SqlNode> contents = new ArrayList<>();
		NodeList children = node.getNode().getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			XNode child = node.newXNode(children.item(i));
			//文本，可能是普通的sql语句，也可能sql存在${}占位符
			if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE
				|| child.getNode().getNodeType() == Node.TEXT_NODE) {
				String data = child.getStringBody("");
				TextSqlNode textSqlNode = new TextSqlNode(data);
				//判断是否动态，对于textSqlNode来说，只要存在占位符就是动态
				if (textSqlNode.isDynamic()) {
					contents.add(textSqlNode);
					//动态标志位
					isDynamic = true;
				} else {
					contents.add(new StaticTextSqlNode(data));
				}
			} else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
				//动态sql标签名
				String nodeName = child.getNode().getNodeName();
				//标签名必须在初始化的Map中存在对应的处理器
				NodeHandler handler = nodeHandlerMap.get(nodeName);
				if (handler == null) {
					throw new BuilderException(
						"Unknown element <" + nodeName + "> in SQL statement.");
				}
				//处理，每一个标签都是一个特殊的SqlNode子类，最终解析完成会放入contents中
				handler.handleNode(child, contents);
				//动态标志位
				isDynamic = true;
			}
		}
		return new MixedSqlNode(contents);
	}

	private interface NodeHandler {

		void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
	}

	/**
	 * 处理<bind/> bind 元素可以从 OGNL 表达式中创建一个变量并将其绑定到上下文，举例：
	 * <select id="selectBlogsLike" resultType="Blog">
	 * <bind name="pattern" value="'%' + _parameter.getTitle() + '%'" />
	 * SELECT * FROM BLOG
	 * WHERE title LIKE #{pattern}
	 * </select>
	 */
	private class BindHandler implements NodeHandler {

		public BindHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			final String name = nodeToHandle.getStringAttribute("name");
			final String expression = nodeToHandle.getStringAttribute("value");
			final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
			targetContents.add(node);
		}
	}

	/**
	 * 处理<trim/> 包含四个属性，举例：
	 * <trim prefix="WHERE" prefixOverrides="AND |OR">
	 * 　　<if test="name != null and name.length()>0"> AND name=#{name}</if>
	 * 　　<if test="gender != null and gender.length()>0"> AND gender=#{gender}</if>
	 * </trim>
	 * prefix：在整个语句片段前加上的内容
	 * suffix：在整个语句片段后加上的内容
	 * prefixOverrides：在整个语句前过滤的内容
	 * suffixOverrides：在整个语句后过滤的内容
	 */
	private class TrimHandler implements NodeHandler {

		public TrimHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			//<trim/>内依然存在动态标签，需要递归处理
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			//解析trim的四个属性
			String prefix = nodeToHandle.getStringAttribute("prefix");
			String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
			String suffix = nodeToHandle.getStringAttribute("suffix");
			String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
			//<trim/>对应TrimSqlNode
			TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides,
				suffix, suffixOverrides);
			targetContents.add(trim);
		}
	}

	/**
	 * 处理<where/> 举例：where 元素只会在至少有一个子元素的条件返回 SQL 子句的情况下才去插入“WHERE”子句。
	 * 而且，若语句的开头为“AND”或“OR”，where 元素也会将它们去除
	 * <where>
	 * <if test="state != null">
	 * state = #{state}
	 * </if>
	 * <if test="title != null">
	 * AND title like #{title}
	 * </if>
	 * <if test="author != null and author.name != null">
	 * AND author_name like #{author.name}
	 * </if>
	 * </where>
	 */
	private class WhereHandler implements NodeHandler {

		public WhereHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			//内部还有标签，递归处理
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			//<where/>对应WhereSqlNode
			WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
			targetContents.add(where);
		}
	}

	/**
	 * 解析<set/> 该标签肯定在<update/> 内部 用于动态包含需要更新的列，而舍去其它的，举例：
	 * <update id="updateAuthorIfNecessary">
	 * update Author
	 * <set>
	 * <if test="username != null">username=#{username},</if>
	 * <if test="password != null">password=#{password},</if>
	 * <if test="email != null">email=#{email},</if>
	 * <if test="bio != null">bio=#{bio}</if>
	 * </set>
	 * where id=#{id}
	 * </update>
	 */
	private class SetHandler implements NodeHandler {

		public SetHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			//处理内部标签
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			//对应SetSqlNode
			SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
			targetContents.add(set);
		}
	}

	/**
	 * 解析<foreach/> 对集合进行遍历，可以用于构建in 条件，举例：
	 * <foreach item="item" index="index" collection="list"
	 * open="(" separator="," close=")">
	 * #{item}
	 * </foreach>
	 */
	private class ForEachHandler implements NodeHandler {

		public ForEachHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			//内部标签递归处理
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			//集合类型，可以处理Map
			String collection = nodeToHandle.getStringAttribute("collection");
			//可迭代对象或者数组时，index 是当前迭代的次数，item 的值是本次迭代获取的元素
			//当使用 Map 对象（或者 Map.Entry 对象的集合）时，index 是键，item 是值
			String item = nodeToHandle.getStringAttribute("item");
			String index = nodeToHandle.getStringAttribute("index");
			// 集合开头和结尾字符
			String open = nodeToHandle.getStringAttribute("open");
			String close = nodeToHandle.getStringAttribute("close");
			//集合每个元素用什么分割
			String separator = nodeToHandle.getStringAttribute("separator");
			//<foreach/>对应ForEachSqlNode
			ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode,
				collection, index, item, open, close, separator);
			targetContents.add(forEachSqlNode);
		}
	}

	/**
	 * 处理<if/>，举例：
	 * <if test="password != null">password=#{password},</if>
	 */
	private class IfHandler implements NodeHandler {

		public IfHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			//<if/>中的sql语句可能存在占位符，需要递归处理
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			//判断语句
			String test = nodeToHandle.getStringAttribute("test");
			IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
			targetContents.add(ifSqlNode);
		}
	}

	/**
	 * 解析<otherwise/> 和 <if/>配对
	 * <otherwise>
	 * AND featured = 1
	 * </otherwise>
	 */
	private class OtherwiseHandler implements NodeHandler {

		public OtherwiseHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
			targetContents.add(mixedSqlNode);
		}
	}

	/**
	 * 解析<choose/> -- <when/> 相当于switch case，举例：
	 * <choose>
	 * <when test="title != null">
	 * AND title like #{title}
	 * </when>
	 * <when test="author != null and author.name != null">
	 * AND author_name like #{author.name}
	 * </when>
	 * <otherwise>
	 * AND featured = 1
	 * </otherwise>
	 * </choose>
	 */
	private class ChooseHandler implements NodeHandler {

		public ChooseHandler() {
			// Prevent Synthetic Access
		}

		@Override
		public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
			List<SqlNode> whenSqlNodes = new ArrayList<>();
			List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
			handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
			SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
			ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
			targetContents.add(chooseSqlNode);
		}

		/**
		 * 处理<when/> 和 <otherwise/>
		 *
		 * @param chooseSqlNode choose节点
		 * @param ifSqlNodes when节点列表
		 * @param defaultSqlNodes otherwise节点列表
		 */
		private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes,
			List<SqlNode> defaultSqlNodes) {
			List<XNode> children = chooseSqlNode.getChildren();
			for (XNode child : children) {
				String nodeName = child.getNode().getNodeName();
				NodeHandler handler = nodeHandlerMap.get(nodeName);
				//分两种分别解析
				if (handler instanceof IfHandler) {
					handler.handleNode(child, ifSqlNodes);
				} else if (handler instanceof OtherwiseHandler) {
					handler.handleNode(child, defaultSqlNodes);
				}
			}
		}

		private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
			SqlNode defaultSqlNode = null;
			if (defaultSqlNodes.size() == 1) {
				defaultSqlNode = defaultSqlNodes.get(0);
				//otherwise节点在一个choose中只能有一个
			} else if (defaultSqlNodes.size() > 1) {
				throw new BuilderException(
					"Too many default (otherwise) elements in choose statement.");
			}
			return defaultSqlNode;
		}
	}

}

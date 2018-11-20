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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 语句标签中<include/> 转换器，转换<include/>引用的sql片段
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

	private final Configuration configuration;
	private final MapperBuilderAssistant builderAssistant;

	public XMLIncludeTransformer(Configuration configuration,
		MapperBuilderAssistant builderAssistant) {
		this.configuration = configuration;
		this.builderAssistant = builderAssistant;
	}
	/**
	 * 解析标签语句中的<include/>比如：
	 * <select id="selectUsers" resultType="map">
	 *      select
	 *      <include refid="userColumns"><property name="alias" value="t1"/></include>,
	 *      <include refid="userColumns"><property name="alias" value="t2"/></include>
	 *      from some_table t1
	 *      cross join some_table t2
	 *  </select>
	 * @param source 标签语句节点
	 */
	public void applyIncludes(Node source) {
		Properties variablesContext = new Properties();
		//将配置参数放入variablesContext中
		Properties configurationVariables = configuration.getVariables();
		if (configurationVariables != null) {
			variablesContext.putAll(configurationVariables);
		}
		applyIncludes(source, variablesContext, false);
	}

	/**
	 * Recursively apply includes through all SQL fragments.
	 *
	 * @param source 标签语句节点及其内部（包括include）任何节点
	 * @param variablesContext 参数
	 */
	private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
		//遍历到include
		if (source.getNodeName().equals("include")) {
			//根据sql片段引用refid，查找对应sql片段节点
			Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
			//获得包含<include/>及其子标签的所有属性
			Properties toIncludeContext = getVariablesContext(source, variablesContext);
			//将<include/>及所有属性传入，递归处理
			applyIncludes(toInclude, toIncludeContext, true);
			if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
				toInclude = source.getOwnerDocument().importNode(toInclude, true);
			}
			source.getParentNode().replaceChild(toInclude, source);
			while (toInclude.hasChildNodes()) {
				toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
			}
			toInclude.getParentNode().removeChild(toInclude);
		} else if (source.getNodeType() == Node.ELEMENT_NODE) {
			if (included && !variablesContext.isEmpty()) {
				// replace variables in attribute values
				NamedNodeMap attributes = source.getAttributes();
				for (int i = 0; i < attributes.getLength(); i++) {
					Node attr = attributes.item(i);
					attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
				}
			}
			NodeList children = source.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				applyIncludes(children.item(i), variablesContext, included);
			}
		} else if (included && source.getNodeType() == Node.TEXT_NODE
			&& !variablesContext.isEmpty()) {
			// replace variables in text node
			source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
		}
	}

	private Node findSqlFragment(String refid, Properties variables) {
		refid = PropertyParser.parse(refid, variables);
		refid = builderAssistant.applyCurrentNamespace(refid, true);
		try {
			XNode nodeToInclude = configuration.getSqlFragments().get(refid);
			return nodeToInclude.getNode().cloneNode(true);
		} catch (IllegalArgumentException e) {
			throw new IncompleteElementException(
				"Could not find SQL statement to include with refid '" + refid + "'", e);
		}
	}

	private String getStringAttribute(Node node, String name) {
		return node.getAttributes().getNamedItem(name).getNodeValue();
	}

	/**
	 * Read placeholders and their values from include node definition.
	 *
	 * @param node Include node instance
	 * @param inheritedVariablesContext Current context used for replace variables in new variables values
	 * @return variables context from include instance (no inherited values)
	 */
	private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
		Map<String, String> declaredProperties = null;
		NodeList children = node.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node n = children.item(i);
			if (n.getNodeType() == Node.ELEMENT_NODE) {
				String name = getStringAttribute(n, "name");
				// Replace variables inside
				String value = PropertyParser
					.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
				if (declaredProperties == null) {
					declaredProperties = new HashMap<>();
				}
				if (declaredProperties.put(name, value) != null) {
					throw new BuilderException(
						"Variable " + name + " defined twice in the same include definition");
				}
			}
		}
		if (declaredProperties == null) {
			return inheritedVariablesContext;
		} else {
			Properties newProperties = new Properties();
			newProperties.putAll(inheritedVariablesContext);
			newProperties.putAll(declaredProperties);
			return newProperties;
		}
	}
}

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
package org.apache.ibatis.scripting.xmltags;

import java.util.List;

/**
 * <choose/>节点对象
 *
 * @author Clinton Begin
 */
public class ChooseSqlNode implements SqlNode {

	//<otherwise/>节点
	private final SqlNode defaultSqlNode;
	//所有的<when/>节点
	private final List<SqlNode> ifSqlNodes;

	public ChooseSqlNode(List<SqlNode> ifSqlNodes, SqlNode defaultSqlNode) {
		this.ifSqlNodes = ifSqlNodes;
		this.defaultSqlNode = defaultSqlNode;
	}

	@Override
	public boolean apply(DynamicContext context) {
		//如果<when/>节点为true
		for (SqlNode sqlNode : ifSqlNodes) {
			//处理为true的<when/>
			if (sqlNode.apply(context)) {
				//只找一个，直接返回
				return true;
			}
		}
		//没有匹配的，直接<otherwise/>
		if (defaultSqlNode != null) {
			defaultSqlNode.apply(context);
			return true;
		}
		return false;
	}
}

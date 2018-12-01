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
package org.apache.ibatis.scripting.xmltags;

import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.session.Configuration;

/**
 * <set/> 节点的SqlNode实现
 *
 * @author Clinton Begin
 */
public class SetSqlNode extends TrimSqlNode {

	private static List<String> suffixList = Arrays.asList(",");

	public SetSqlNode(Configuration configuration, SqlNode contents) {
		/**
		 * update Author
		 *     <set>
		 *       <if test="username != null">username=#{username},</if>
		 *       <if test="password != null">password=#{password},</if>
		 *       <if test="email != null">email=#{email},</if>
		 *       <if test="bio != null">bio=#{bio}</if>
		 *     </set>
		 *   where id=#{id}
		 *   比如这种prefix肯定要有set，然后最后一个需要过滤后缀的,逗号
		 */
		super(configuration, contents, "SET", null, null, suffixList);
	}

}

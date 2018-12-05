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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

	//类型处理器注册中心
	private final TypeHandlerRegistry typeHandlerRegistry;
	//Mapper方法和Mapper标签对应实体
	private final MappedStatement mappedStatement;
	//参数对象
	private final Object parameterObject;
	//sql
	private final BoundSql boundSql;
	private final Configuration configuration;

	public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject,
		BoundSql boundSql) {
		this.mappedStatement = mappedStatement;
		this.configuration = mappedStatement.getConfiguration();
		this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
		this.parameterObject = parameterObject;
		this.boundSql = boundSql;
	}

	@Override
	public Object getParameterObject() {
		return parameterObject;
	}

	/**
	 * 将参数值设置到PreparedStatement中对应的参数位置上，具体的流程是：
	 * Mybatis启动时，会解析xml、annotation上配置的sql，此时sql上待传输的数据
	 * 会用？代替；当调用接口查询时，会将真正的参数值传递过来，此时根据之前解析的
	 * Mapper文件的sql，将参数真正放入PreparedStatement
	 */
	@Override
	public void setParameters(PreparedStatement ps) {
		ErrorContext.instance().activity("setting parameters")
			.object(mappedStatement.getParameterMap().getId());
		//获得参数映射列表
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		if (parameterMappings != null) {
			//存在参数，遍历每一个参数映射
			for (int i = 0; i < parameterMappings.size(); i++) {
				ParameterMapping parameterMapping = parameterMappings.get(i);
				if (parameterMapping.getMode() != ParameterMode.OUT) {
					Object value;
					//参数名
					String propertyName = parameterMapping.getProperty();
					//额外参数列表中是否存在该参数
					if (boundSql.hasAdditionalParameter(
						propertyName)) { // issue #448 ask first for additional params
						value = boundSql.getAdditionalParameter(propertyName);
					} else if (parameterObject == null) {
						value = null;
					} else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
						//存在参数类型的类型处理器，可以处理，先赋值，下面做类型处理
						value = parameterObject;
					} else {
						//参数是元数据的形式
						MetaObject metaObject = configuration.newMetaObject(parameterObject);
						value = metaObject.getValue(propertyName);
					}
					//参数的类型处理器
					TypeHandler typeHandler = parameterMapping.getTypeHandler();
					//设置jdbcType
					JdbcType jdbcType = parameterMapping.getJdbcType();
					if (value == null && jdbcType == null) {
						jdbcType = configuration.getJdbcTypeForNull();
					}
					try {
						//typehandler给sql设置真实的参数值
						typeHandler.setParameter(ps, i + 1, value, jdbcType);
					} catch (TypeException e) {
						throw new TypeException(
							"Could not set parameters for mapping: " + parameterMapping
								+ ". Cause: " + e, e);
					} catch (SQLException e) {
						throw new TypeException(
							"Could not set parameters for mapping: " + parameterMapping
								+ ". Cause: " + e, e);
					}
				}
			}
		}
	}

}

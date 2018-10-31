/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.datasource.unpooled;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

	private static final String DRIVER_PROPERTY_PREFIX = "driver.";
	private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

	protected DataSource dataSource;

	public UnpooledDataSourceFactory() {
		this.dataSource = new UnpooledDataSource();
	}

	@Override
	public void setProperties(Properties properties) {
		Properties driverProperties = new Properties();
		//将UnpooledDataSourceFactory封装成MetaObject
		MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
		//遍历要设置的属性
		for (Object key : properties.keySet()) {
			String propertyName = (String) key;
			if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
				//以driver.开头
				String value = properties.getProperty(propertyName);
				//去除driver.前缀放入driverProperties中
				driverProperties
					.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
			} else if (metaDataSource.hasSetter(propertyName)) {
				//datasource存在属性对应的setter
				String value = (String) properties.get(propertyName);
				//转换属性所需类型
				Object convertedValue = convertValue(metaDataSource, propertyName, value);
				//给UnpooledDataSource设置属性
				metaDataSource.setValue(propertyName, convertedValue);
			} else {
				throw new DataSourceException("Unknown DataSource property: " + propertyName);
			}
		}
		if (driverProperties.size() > 0) {
			//所有以driver.开头的都设置在属性driverProperties中
			metaDataSource.setValue("driverProperties", driverProperties);
		}
	}

	@Override
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * 将propertyName对应的属性值value转换成metaDataSource中封装的UnpooledDataSource对应属性需要的类型
	 * @param metaDataSource UnpooledDataSource对应的MetaObject
	 * @param propertyName 属性名
	 * @param value 属性值
	 * @return
	 */
	private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
		Object convertedValue = value;
		Class<?> targetType = metaDataSource.getSetterType(propertyName);
		if (targetType == Integer.class || targetType == int.class) {
			convertedValue = Integer.valueOf(value);
		} else if (targetType == Long.class || targetType == long.class) {
			convertedValue = Long.valueOf(value);
		} else if (targetType == Boolean.class || targetType == boolean.class) {
			convertedValue = Boolean.valueOf(value);
		}
		return convertedValue;
	}

}

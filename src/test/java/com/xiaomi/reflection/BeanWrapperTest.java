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
package com.xiaomi.reflection;

import org.apache.ibatis.domain.misc.CustomBeanWrapperFactory;
import org.apache.ibatis.domain.misc.RichType;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.BeanWrapper;
import org.apache.ibatis.submitted.result_handler_type.ObjectFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;

/**
 * Created by william on 2018/10/28.
 */
public class BeanWrapperTest {

	@Test
	public void getBeanPropertyTest() {
		RichType richType = new RichType();
		richType.setRichType(new RichType());
		richType.getRichType().setRichMap(new HashMap<>());
		richType.getRichType().getRichMap().put("name", "zhangsan");
		BeanWrapper beanWrapper = initBeanWrapper(richType);
		PropertyTokenizer propertyTokenizer = new PropertyTokenizer("richType.richMap.name");
		Object value = beanWrapper.get(propertyTokenizer);

		Assert.assertTrue(value instanceof RichType);
		RichType rt = (RichType) value;
		Assert.assertEquals(rt.getRichMap().size(), 1);
		Assert.assertEquals(rt.getRichMap().get("name"), "zhangsan");
	}

	private BeanWrapper initBeanWrapper(RichType richType) {
		MetaObject metaObject = MetaObject
			.forObject(richType, SystemMetaObject.DEFAULT_OBJECT_FACTORY,
				new CustomBeanWrapperFactory(), new DefaultReflectorFactory());
		return new BeanWrapper(metaObject, richType);
	}


	@Test
	public void getCollectionNoChildTest() {
		RichType richType = new RichType();
		richType.getRichList().add("zhangsan");

		BeanWrapper beanWrapper = initBeanWrapper(richType);
		PropertyTokenizer propertyTokenizer = new PropertyTokenizer("richList[1]");
		Object value = beanWrapper.get(propertyTokenizer);

		Assert.assertTrue(value instanceof String);
		Assert.assertEquals((String) value, "zhangsan");
	}


	@Test
	public void getCollectionHasChildTest() {
		RichType richType = new RichType();

		RichType rt = new RichType();
		rt.setRichProperty("lisi");
		richType.getRichList().add(rt);

		BeanWrapper beanWrapper = initBeanWrapper(richType);
		PropertyTokenizer propertyTokenizer = new PropertyTokenizer("richList[1].richProperty");
		Object value = beanWrapper.get(propertyTokenizer);

		Assert.assertTrue(value instanceof RichType);
		Assert.assertEquals(((RichType) value).getRichProperty(), "lisi");
	}


	@Test
	public void getGetterTypeTest() {
		RichType richType = new RichType();
		RichType rt = new RichType();
		rt.setRichProperty("lisi");
		richType.getRichList().add(rt);

		BeanWrapper beanWrapper = initBeanWrapper(richType);
		Class<?> getterType = beanWrapper.getGetterType("richList[1].richProperty");

		Assert.assertEquals(String.class, getterType);
	}

}

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

import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by william on 2018/10/28.
 */
public class PropertyTokenizerTest {

    @Test
    public void fun() {
        String fullname = "student.name";
        PropertyTokenizer propertyTokenizer = new PropertyTokenizer(fullname);
        Assert.assertEquals(propertyTokenizer.getName(), "student");
        Assert.assertEquals(propertyTokenizer.getChildren(), "name");
        Assert.assertEquals(propertyTokenizer.getIndexedName(), propertyTokenizer.getName());
        Assert.assertNull(propertyTokenizer.getIndex());
    }

    @Test
    public void fun2() {
        String fullname = "student[0].name";
        PropertyTokenizer propertyTokenizer = new PropertyTokenizer(fullname);
        Assert.assertEquals(propertyTokenizer.getName(), "student");
        Assert.assertEquals(propertyTokenizer.getChildren(), "name");
        Assert.assertEquals(propertyTokenizer.getIndexedName(), "student[0]");
        Assert.assertEquals(propertyTokenizer.getIndex(), "0");
    }

    @Test
    public void fun3() {
        String fullname = "class[zhangsan].age";
        PropertyTokenizer propertyTokenizer = new PropertyTokenizer(fullname);
        Assert.assertEquals(propertyTokenizer.getName(), "class");
        Assert.assertEquals(propertyTokenizer.getChildren(), "age");
        Assert.assertEquals(propertyTokenizer.getIndexedName(), "class[zhangsan]");
        Assert.assertEquals(propertyTokenizer.getIndex(), "zhangsan");
    }
}

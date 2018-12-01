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
package com.xiaomi.type;

import org.junit.Test;

/**
 * Created by william on 2018/11/3.
 */
public class EnumTest {

    enum MyEnum {
        ONE, TWO, THREE
    }

    @Test
    public void enumConstantsTest() {
        MyEnum[] enumConstants = MyEnum.class.getEnumConstants();
        for (MyEnum enumConstant : enumConstants) {
            StringBuilder sb = new StringBuilder(enumConstant.name());
            sb.append(">>>>");
            sb.append(enumConstant.ordinal());

            System.out.println(sb.toString());
        }

    }


    @Test
    public void enumNameTest() {
        MyEnum one = MyEnum.valueOf("ONE");
        System.out.println(one);
    }
}

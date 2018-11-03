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
}

package com.xiaomi.reflection;

import static java.util.stream.Collectors.toList;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

public class ReflectionTest {


    abstract class A implements C {
        abstract void run();

        public void sleep() {

        }

//        public void seat() {
//
//        }
    }

    class B extends A{
        public void eat() {

        }

        @Override
        void run() {

        }

        @Override
        public void seat() {

        }
    }

    @Test
    public void fun1() {
        B b = new B();
        for (Class<?> anInterface : A.class.getInterfaces()) {
            System.out.println(Arrays.stream(anInterface.getMethods()).map(Method::getName).collect(toList()));
        }

        System.out.println("----------------------");

        for (Method declaredMethod : b.getClass().getDeclaredMethods()) {
            System.out.println(declaredMethod.getName());
        }
    }
}

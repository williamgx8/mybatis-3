package org.apache.ibatis.reflection.typeparam;

public abstract class Person<H,W> implements Animal<H,W,String>{

	@Override
	public String getName(String name) {
		return name;
	}
}

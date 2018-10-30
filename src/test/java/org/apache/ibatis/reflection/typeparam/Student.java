package org.apache.ibatis.reflection.typeparam;

public class Student extends Person<Double,Double>{

	@Override
	public Double getHeight() {
		return null;
	}

	@Override
	public Double getWight() {
		return null;
	}
}

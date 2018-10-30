package org.apache.ibatis.reflection.typeparam;

public interface Animal<H,W,N>{

	H getHeight();

	W getWight();

	N getName(N name);
}

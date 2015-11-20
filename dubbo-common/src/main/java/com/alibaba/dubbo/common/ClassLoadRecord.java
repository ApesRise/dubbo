package com.alibaba.dubbo.common;

/**
 * @author weigao
 * @since 15/11/18
 */
public class ClassLoadRecord {
    private static ClassLoadRecord instance = new ClassLoadRecord();

    public static ClassLoader getClassLoader() {
        return instance.getClass().getClassLoader();
    }
}

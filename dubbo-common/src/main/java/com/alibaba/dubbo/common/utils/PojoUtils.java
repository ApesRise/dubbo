/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.utils;

import com.alibaba.dubbo.common.ClassLoadRecord;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * PojoUtils. Travel object deeply, and convert complex type to simple type.
 * <p>
 * Simple type below will be remained:
 * <ul>
 * <li> Primitive Type, also include <b>String</b>, <b>Number</b>(Integer, Long), <b>Date</b>
 * <li> Array of Primitive Type
 * <li> Collection, eg: List, Map, Set etc.
 * </ul>
 * <p>
 * Other type will be covert to a map which contains the attributes and value pair of object.
 * 
 * @author william.liangf
 * @author ding.lid
 */
public class PojoUtils {
    
    private static final ConcurrentMap<String, Method>  NAME_METHODS_CACHE = new ConcurrentHashMap<String, Method>();
    private static final ConcurrentMap<Class<?>, ConcurrentMap<String, Field>> CLASS_FIELD_CACHE = 
        new ConcurrentHashMap<Class<?>, ConcurrentMap<String, Field>>();

    public static Object[] generalize(Object[] objs) {
        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i ++) {
            dests[i] = generalize(objs[i]);
        }
        return dests;
    }

    public static Object[] realize(Object[] objs, Class<?>[] types) {
        if (objs.length != types.length)
            throw new IllegalArgumentException("args.length != types.length");
        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i ++) {
            dests[i] = realize(objs[i], types[i]);
        }
        return dests;
    }

    public static Object[] realize(Object[] objs, Class<?>[] types, Type[] gtypes) {
        if (objs.length != types.length
                || objs.length != gtypes.length)
            throw new IllegalArgumentException("args.length != types.length");
        Object[] dests = new Object[objs.length];
        for (int i = 0; i < objs.length; i ++) {
            dests[i] = realize(objs[i], types[i], gtypes[i]);
        }
        return dests;
    }

    public static Object generalize(Object pojo) {
        return generalize(pojo, new IdentityHashMap<Object, Object>());
    }

    @SuppressWarnings("unchecked")
    private static Object generalize(Object pojo, Map<Object, Object> history) {
        if (pojo == null) {
            return null;
        }
        
        if (pojo instanceof Enum<?>) {
            return ((Enum<?>)pojo).name();
        }
        if (pojo.getClass().isArray() 
        		&& Enum.class.isAssignableFrom(
        				pojo.getClass().getComponentType())) {
        	int len = Array.getLength(pojo);
        	String[] values = new String[len];
        	for (int i = 0; i < len; i ++) {
        		values[i] = ((Enum<?>)Array.get(pojo, i)).name();
        	}
            return values;
        }
        
        if (ReflectUtils.isPrimitives(pojo.getClass())) {
            return pojo;
        }

        if (pojo instanceof Class) {
            return ((Class)pojo).getName();
        }

        Object o = history.get(pojo);
        if(o != null){
            return o;
        }
        history.put(pojo, pojo);
        
        if (pojo.getClass().isArray()) {
            int len = Array.getLength(pojo);
            Object[] dest = new Object[len];
            history.put(pojo, dest);
            for (int i = 0; i < len; i ++) {
                Object obj = Array.get(pojo, i);
                dest[i] = generalize(obj, history);
            }
            return dest;
        }
        if (pojo instanceof Collection<?>) {
            Collection<Object> src = (Collection<Object>)pojo;
            int len = src.size();
            Collection<Object> dest = (pojo instanceof List<?>) ? new ArrayList<Object>(len) : new HashSet<Object>(len);
            history.put(pojo, dest);
            for (Object obj : src) {
                dest.add(generalize(obj, history));
            }
            return dest;
        }
        if (pojo instanceof Map<?, ?>) {
            Map<Object, Object> src = (Map<Object, Object>)pojo;
            Map<Object, Object> dest= createMap(src);
            history.put(pojo, dest);
            for (Map.Entry<Object, Object> obj : src.entrySet()) {
            	dest.put(generalize(obj.getKey(), history), generalize(obj.getValue(), history));
            }
            return dest;
        }
        Map<String, Object> map = new HashMap<String, Object>();
        history.put(pojo, map);
        map.put("class", pojo.getClass().getName());
        for (Method method : pojo.getClass().getMethods()) {
            if (ReflectUtils.isBeanPropertyReadMethod(method)) {
                try {
                    map.put(ReflectUtils.getPropertyNameFromBeanReadMethod(method),
                            generalize(method.invoke(pojo), history));
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        }
        // public field
        for(Field field : pojo.getClass().getFields()) {
            if (ReflectUtils.isPublicInstanceField(field)) {
                try {
                    Object fieldValue = field.get(pojo);
                    // public filed同时也有get/set方法，如果get/set存取的不是前面那个 public field 该如何处理
                    if (history.containsKey(pojo)) {
                        Object pojoGenerilizedValue = history.get(pojo);
                        if (pojoGenerilizedValue instanceof Map
                            && ((Map)pojoGenerilizedValue).containsKey(field.getName())) {
                            continue;
                        }
                    }
                    if (fieldValue != null) {
                        map.put(field.getName(), generalize(fieldValue, history));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        }
        return map;
    }
    
    public static Object realize(Object pojo, Class<?> type) {
        return realize0(pojo, type, null , new IdentityHashMap<Object, Object>());
    }
    
    public static Object realize(Object pojo, Class<?> type, Type genericType) {
        return realize0(pojo, type, genericType, new IdentityHashMap<Object, Object>());
    }
    
    private static class PojoInvocationHandler implements InvocationHandler {
        
        private Map<Object, Object> map;

        public PojoInvocationHandler(Map<Object, Object> map) {
            this.map = map;
        }

        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(map, args);
            }
            String methodName = method.getName();
            Object value = null;
            if (methodName.length() > 3 && methodName.startsWith("get")) {
                value = map.get(methodName.substring(3, 4).toLowerCase() + methodName.substring(4));
            } else if (methodName.length() > 2 && methodName.startsWith("is")) {
                value = map.get(methodName.substring(2, 3).toLowerCase() + methodName.substring(3));
            } else {
                value = map.get(methodName.substring(0, 1).toLowerCase() + methodName.substring(1));
            }
            if (value instanceof Map<?,?> && ! Map.class.isAssignableFrom(method.getReturnType())) {
                value = realize0((Map<String, Object>) value, method.getReturnType(), null, new IdentityHashMap<Object, Object>());
            }
            return value;
        }
    }
    
    @SuppressWarnings("unchecked")
	private static Collection<Object> createCollection(Class<?> type, int len) {
    	if (type.isAssignableFrom(ArrayList.class)) {
    		return  new ArrayList<Object>(len);
    	}
    	if (type.isAssignableFrom(HashSet.class)) {
    		return new HashSet<Object>(len);
    	}
    	if (! type.isInterface() && ! Modifier.isAbstract(type.getModifiers())) {
    		try {
				return (Collection<Object>) type.newInstance();
			} catch (Exception e) {
				// ignore
			}
    	}
    	return new ArrayList<Object>();
    }

    private static Map createMap(Map src) {
        Class<? extends Map> cl = src.getClass();
        Map result = null;
        if (HashMap.class == cl) {
            result = new HashMap();
        } else if (Hashtable.class == cl) {
            result = new Hashtable();
        } else if (IdentityHashMap.class == cl) {
            result = new IdentityHashMap();
        } else if (LinkedHashMap.class == cl) {
            result = new LinkedHashMap();
        } else if (Properties.class == cl) {
            result = new Properties();
        } else if (TreeMap.class == cl) {
            result = new TreeMap();
        } else if (WeakHashMap.class == cl) {
            return new WeakHashMap();
        } else if (ConcurrentHashMap.class == cl) {
            result = new ConcurrentHashMap();
        } else if (ConcurrentSkipListMap.class == cl) {
            result = new ConcurrentSkipListMap();
        } else {
            try {
                result = cl.newInstance();
            } catch (Exception e) { /* ignore */ }

            if (result == null) {
                try {
                    Constructor<?> constructor = cl.getConstructor(Map.class);
                    result = (Map)constructor.newInstance(Collections.EMPTY_MAP);
                } catch (Exception e) { /* ignore */ }
            }
        }

        if (result == null) {
            result = new HashMap<Object, Object>();
        }

        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object realize0(Object pojo, Class<?> type, Type genericType, final Map<Object, Object> history) {
        if (pojo == null) {
            return null;
        }
        
        if (type != null && type.isEnum() 
        		&& pojo.getClass() == String.class) {
    		return Enum.valueOf((Class<Enum>)type, (String)pojo);
    	}

        if (ReflectUtils.isPrimitives(pojo.getClass())
        		&& ! (type != null && type.isArray()
        				&& type.getComponentType().isEnum()
        				&& pojo.getClass() == String[].class)) {
            return CompatibleTypeUtils.compatibleTypeConvert(pojo, type);
        }

        Object o = history.get(pojo);
        
        if(o != null){
            return o;
        }
        
        history.put(pojo, pojo);
        
        if (pojo.getClass().isArray()) {
        	if (Collection.class.isAssignableFrom(type)) {
        		Class<?> ctype = pojo.getClass().getComponentType();
	            int len = Array.getLength(pojo);
        		Collection dest = createCollection(type, len);
                history.put(pojo, dest);
        		for (int i = 0; i < len; i ++) {
	                Object obj = Array.get(pojo, i);
                    Object value = realize0(obj, ctype, null, history);
	                dest.add(value);
	            }
	            return dest;
        	} else {
	        	Class<?> ctype = (type != null && type.isArray() ? type.getComponentType() : pojo.getClass().getComponentType());
	            int len = Array.getLength(pojo);
	            Object dest = Array.newInstance(ctype, len);
                history.put(pojo, dest);
	            for (int i = 0; i < len; i ++) {
	                Object obj = Array.get(pojo, i);
                    Object value = realize0(obj, ctype, null, history);
	                Array.set(dest, i, value);
	            }
	            return dest;
            }
        }
        
        if (pojo instanceof Collection<?>) {
        	if (type.isArray()) {
        		Class<?> ctype = type.getComponentType();
                Collection<Object> src = (Collection<Object>)pojo;
                int len = src.size();
                Object dest = Array.newInstance(ctype, len);
                history.put(pojo, dest);
                int i = 0;
                for (Object obj : src) {
                    Object value = realize0(obj, ctype, null, history);
                    Array.set(dest, i, value);
                    i ++;
                }
                return dest;
        	} else {
        		Collection<Object> src = (Collection<Object>)pojo;
                int len = src.size();
                Collection<Object> dest = createCollection(type, len);
                history.put(pojo, dest);
                for (Object obj : src) {
                    Type keyType = getGenericClassByIndex(genericType, 0);
                    Class<?> keyClazz = obj.getClass() ;
                    if ( keyType instanceof Class){
                      keyClazz = (Class<?>)keyType;
                    } 
                	Object value = realize0(obj, keyClazz, keyType, history);
                    dest.add(value);
                }
                return dest;
        	}
        }
        
        if (pojo instanceof Map<?, ?> && type != null) {
        	Object className = ((Map<Object, Object>)pojo).get("class");
            if (className instanceof String) {
                try {
                    type = ClassHelper.forName((String)className);
                } catch (ClassNotFoundException e) {
                    // ignore
                }
            }
            Map<Object, Object> map ;
            // 返回值类型不是方法签名类型的子集 并且 不是接口类型
            if (! type.isInterface()
                    && ! type.isAssignableFrom(pojo.getClass())){
                try {
                    map = (Map<Object,Object>)type.newInstance();
                } catch (Exception e) {
                    //ignore error
                    map = (Map<Object, Object>)pojo;
                }
            }else {
                map = (Map<Object, Object>)pojo;
            }
            
            if (Map.class.isAssignableFrom(type) || type == Object.class) {
            	final Map<Object, Object> result = createMap(map);
                history.put(pojo, result);
            	for (Map.Entry<Object, Object> entry : map.entrySet()) {
            	    Type keyType = getGenericClassByIndex(genericType, 0);
            	    Type valueType = getGenericClassByIndex(genericType, 1);
            	    Class<?> keyClazz;
            	    if ( keyType instanceof Class){
            	        keyClazz = (Class<?>)keyType;
            	    } else {
            	        keyClazz = entry.getKey() == null ? null : entry.getKey().getClass();
            	    }
            	    Class<?> valueClazz;
                    if ( valueType instanceof Class){
                        valueClazz = (Class<?>)valueType;
                    } else {
                        valueClazz = entry.getValue() == null ? null : entry.getValue().getClass() ;
                    }
            	    
            	    Object key = keyClazz == null ? entry.getKey() : realize0(entry.getKey(), keyClazz, keyType, history);
            	    Object value = valueClazz == null ? entry.getValue() : realize0(entry.getValue(), valueClazz, valueType, history);
            	     result.put(key, value);
            	}
        		return result;
        	} else if (type.isInterface()) {
                //Thread.currentThread().getContextClassLoader()
        	    Object dest = Proxy.newProxyInstance(ClassLoadRecord.getClassLoader(), new Class<?>[]{type}, new PojoInvocationHandler(map));
                history.put(pojo, dest);
                return dest;
            } else {
                Object dest = newInstance(type);
                history.put(pojo, dest);
                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                	Object key = entry.getKey();
                	if (key instanceof String) {
	                    String name = (String) key;
	                    Object value = entry.getValue();
	                    if (value != null) {
	                        Method method = getSetterMethod(dest.getClass(), name, value.getClass());
                            Field field = getField(dest.getClass(), name);
	                        if (method != null) {
	                            if (! method.isAccessible())
	                                method.setAccessible(true);
	                            Type ptype = method.getGenericParameterTypes()[0];
	                            value = realize0(value, method.getParameterTypes()[0], ptype, history);
	                            try {
	                                method.invoke(dest, value);
	                            } catch (Exception e) {
	                                e.printStackTrace();
	                                throw new RuntimeException("Failed to set pojo " + dest.getClass().getSimpleName() + " property " + name
	                                        + " value " + value + "(" + value.getClass() + "), cause: " + e.getMessage(), e);
	                            }
	                        } else if (field != null) {
                                value = realize0(value, field.getType(), field.getGenericType(), history);
                                try {
                                    field.set(dest, value);
                                } catch (IllegalAccessException e) {
                                    throw new RuntimeException(
                                        new StringBuilder(32)
                                            .append("Failed to set filed ")
                                            .append(name)
                                            .append(" of pojo ")
                                            .append(dest.getClass().getName())
                                            .append( " : " )
                                            .append(e.getMessage()).toString(),
                                        e);
                                }
                            }
	                    }
                	}
                }
                if (dest instanceof Throwable) {
                    Object message = map.get("message");
                    if (message instanceof String) {
                        try {
                            Field filed = Throwable.class.getDeclaredField("detailMessage");
                            if(! filed.isAccessible()) {
                                filed.setAccessible(true);
                            }
                            filed.set(dest, (String) message);
                        } catch (Exception e) {
                        }
                    }
                }
                return dest;
            }
        }
        return pojo;
    }
    
    /**
     * 获取范型的类型 
     * @param genericType
     * @param index
     * @return List<Person>  返回Person.class ,Map<String,Person> index=0 返回String.class index=1 返回Person.class
     */
    private static Type getGenericClassByIndex(Type genericType, int index){
        Type clazz = null ;
        //范型参数转换 
        if (genericType instanceof ParameterizedType){
            ParameterizedType t = (ParameterizedType)genericType;
            Type[] types = t.getActualTypeArguments();
            clazz = types[index];
        }
        return clazz;
    }
    
    private static Object newInstance(Class<?> cls) {
        try {
            return cls.newInstance();
        } catch (Throwable t) {
            try {
                Constructor<?>[] constructors = cls.getConstructors();
                if (constructors != null && constructors.length == 0) {
                    throw new RuntimeException("Illegal constructor: " + cls.getName());
                }
                Constructor<?> constructor = constructors[0];
                if (constructor.getParameterTypes().length > 0) {
                    for (Constructor<?> c : constructors) {
                        if (c.getParameterTypes().length < 
                                constructor.getParameterTypes().length) {
                            constructor = c;
                            if (constructor.getParameterTypes().length == 0) {
                                break;
                            }
                        }
                    }
                }
                return constructor.newInstance(new Object[constructor.getParameterTypes().length]);
            } catch (InstantiationException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage(), e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    private static Method getSetterMethod(Class<?> cls, String property, Class<?> valueCls) {
        String name = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
        Method method = NAME_METHODS_CACHE.get(cls.getName() + "." + name + "(" +valueCls.getName() + ")");
            if(method == null){
                try {
                method = cls.getMethod(name, valueCls);
                } catch (NoSuchMethodException e) {
                    for (Method m : cls.getMethods()) {
                        if (ReflectUtils.isBeanPropertyWriteMethod(m)
                                && m.getName().equals(name)) {
                             method = m;
                        }
                    }
                }
                if(method != null){
                    NAME_METHODS_CACHE.put(cls.getName() + "." + name + "(" +valueCls.getName() + ")", method);
                }
            }
       return method;
    }
    
    private static Field getField(Class<?> cls, String fieldName) {
        Field result = null;
        if (CLASS_FIELD_CACHE.containsKey(cls) 
            && CLASS_FIELD_CACHE.get(cls).containsKey(fieldName)) {
            return CLASS_FIELD_CACHE.get(cls).get(fieldName);
        }
        try {
            result = cls.getField(fieldName);
        } catch (NoSuchFieldException e) {
            for(Field field : cls.getFields()) {
                if (fieldName.equals(field.getName()) 
                    && ReflectUtils.isPublicInstanceField(field)) {
                    result = field;
                    break;
                }
            }
        }
        if (result != null) {
            ConcurrentMap<String, Field> fields = CLASS_FIELD_CACHE.get(cls);
            if (fields == null) {
                fields = new ConcurrentHashMap<String, Field>();
                CLASS_FIELD_CACHE.putIfAbsent(cls, fields);
            }
            fields = CLASS_FIELD_CACHE.get(cls);
            fields.putIfAbsent(fieldName, result);
        }
        return result;
    }
    
    public static boolean isPojo(Class<?> cls) {
        return ! ReflectUtils.isPrimitives(cls)
                && ! Collection.class.isAssignableFrom(cls) 
                && ! Map.class.isAssignableFrom(cls);
    }

}
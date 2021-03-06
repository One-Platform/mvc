/*
 * Copyright 2007-2009 the original author or authors.
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
package com.sinosoft.one.mvc.web.impl.module;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import com.sinosoft.one.mvc.web.annotation.AsSuperController;
import com.sinosoft.one.mvc.web.annotation.Ignored;
import com.sinosoft.one.mvc.web.annotation.ReqMethod;
import com.sinosoft.one.mvc.web.annotation.rest.Delete;
import com.sinosoft.one.mvc.web.annotation.rest.Get;
import com.sinosoft.one.mvc.web.annotation.rest.Head;
import com.sinosoft.one.mvc.web.annotation.rest.Options;
import com.sinosoft.one.mvc.web.annotation.rest.Post;
import com.sinosoft.one.mvc.web.annotation.rest.Put;
import com.sinosoft.one.mvc.web.annotation.rest.Trace;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.ClassUtils;

/**
 *
 *
 *
 */
public class ControllerRef {

    private static Log logger = LogFactory.getLog(ControllerRef.class);

    private String[] mappingPaths;

    // e.g. UserController, UserInfoController的controllerName分别是user和userInfo
    private String controllerName;

    private Class<?> controllerClass;

    private Object controllerObject;

    List<MethodRef> actions;

    public ControllerRef(String[] mappingPaths, String controllerName, Object controllerObject,
                         Class<?> controllerClass) {
        setMappingPaths(mappingPaths);
        setControllerName(controllerName);
        setControllerObject(controllerObject);
        setControllerClass(controllerClass);
    }

    public MethodRef[] getActions() {
        if (this.actions == null) {
            init();
        }
        return actions.toArray(new MethodRef[0]);
    }

    private void init() {
        List<MethodRef> actions = new LinkedList<MethodRef>();
        Class<?> clz = controllerClass;
        //
        List<Method> pastMethods = new LinkedList<Method>();
        while (true) {
            Method[] declaredMethods = clz.getDeclaredMethods();

            //增加如果一个class中有多个同样的ReqMethod，且无value的情况，采用按照字符串的方式排序，并自动将排在第一ReqMethod设置为缺省，其余的自动增加methodName
            //增加sort排序,按字母顺序进行排序，字母在前的方法排至前面，从0位开始如果相等按第0+1个字母排序,以此类推。完全一致，方法名称短的排在方法名称长的前面
            Arrays.sort(declaredMethods, new Comparator<Method>() {
                public int compare(Method o1, Method o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            });
            //检查是否有多个ReqMethod类型相同且ReqMethod.value均为空的情况
            Map<ReqMethod,Method> checkReqMethod = new HashMap<ReqMethod, Method>();

            for (Method method : declaredMethods) {
                if (quicklyPass(pastMethods, method, controllerClass)) {
                    continue;
                }

                Map<ReqMethod, String[]> shotcutMappings = collectsShotcutMappings(method);
                if (shotcutMappings.size() == 0) {
                    if (ignoresCommonMethod(method)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("ignores common methods of controller "
                                    + controllerClass.getName() + "#" + method.getName());
                        }
                    } else {
                        // TODO: 这个代码是为了使从0.9到1.0比较顺畅而做的判断，201007之后可以考虑删除掉
                        if ("get".equals(method.getName()) || "index".equals(method.getName())) {
                            // 这个异常的意思是让大家在get/index上明确标注@Get，请注意@Get的意思
                            throw new IllegalArgumentException("please add @Get to "
                                    + controllerClass.getName() + "#" + method.getName());
                        }
                        if ("post".equals(method.getName()) || "delete".equals(method.getName())
                                || "put".equals(method.getName())) {
                            // 这个异常的意思是让大家在post/delete/put上明确标注@Get/@Delete/@Put，请注意@Get的意思
                            throw new IllegalArgumentException("please add @"
                                    + StringUtils.capitalize(method.getName()) + " to "
                                    + controllerClass.getName() + "#" + method.getName());
                        }
                        shotcutMappings = new HashMap<ReqMethod, String[]>();
                        shotcutMappings.put(ReqMethod.GET, new String[] { "/" + method.getName() });
                        shotcutMappings
                                .put(ReqMethod.POST, new String[] { "/" + method.getName() });
                    }
                }
                if (shotcutMappings.size() > 0) {
                    MethodRef methodRef = new MethodRef();
                    for (Map.Entry<ReqMethod, String[]> entry : shotcutMappings.entrySet()) {

                        //处理多个ReqMethod类型相同且ReqMethod.value均为空的情况
                        if(entry.getValue().length==1&&entry.getValue()[0].equals("")){
                            if(checkReqMethod.get(entry.getKey())==null){
                                checkReqMethod.put(entry.getKey(),method);
                            }else{
                                entry.setValue(new String[] { "/" + method.getName() });
                            }
                        }

                        if(logger.isDebugEnabled()){
                            logger.debug("[MethodPath] create:"+entry.getKey()+"="+Arrays.toString(entry.getValue()));
                        }
                        methodRef.addMapping(entry.getKey(), entry.getValue());
                    }
                    methodRef.setMethod(method);
                    actions.add(methodRef);
                }
            }
            for (int i = 0; i < declaredMethods.length; i++) {
                pastMethods.add(declaredMethods[i]);
            }
            clz = clz.getSuperclass();
            if (clz == null || clz.getAnnotation(AsSuperController.class) == null) {
                break;
            }
        }
        this.actions = actions;
    }

    //获取method上面定义的ReqMethod与ReqMethod.value的键值对应关系，一个method可以有多个ReqMethod
    private Map<ReqMethod, String[]> collectsShotcutMappings(Method method) {
        Map<ReqMethod, String[]> restMethods = new HashMap<ReqMethod, String[]>();
        Annotation[] annotations = method.getAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation instanceof Delete) {
                restMethods.put(ReqMethod.DELETE, ((Delete) annotation).value());
            } else if (annotation instanceof Get) {
                restMethods.put(ReqMethod.GET, ((Get) annotation).value());
            } else if (annotation instanceof Head) {
                restMethods.put(ReqMethod.HEAD, ((Head) annotation).value());
            } else if (annotation instanceof Options) {
                restMethods.put(ReqMethod.OPTIONS, ((Options) annotation).value());
            } else if (annotation instanceof Post) {
                restMethods.put(ReqMethod.POST, ((Post) annotation).value());
            } else if (annotation instanceof Put) {
                restMethods.put(ReqMethod.PUT, ((Put) annotation).value());
            } else if (annotation instanceof Trace) {
                restMethods.put(ReqMethod.TRACE, ((Trace) annotation).value());
            } else {}
        }

        for (String[] paths : restMethods.values()) {
            for (int i = 0; i < paths.length; i++) {
                if (paths[i].equals("/")) {
                    paths[i] = "";
                } else if (paths[i].length() > 0 && paths[i].charAt(0) != '/') {
                    paths[i] = "/" + paths[i];
                }
                if (paths[i].length() > 1 && paths[i].endsWith("/")) {
                    if (paths[i].endsWith("//")) {
                        throw new IllegalArgumentException("invalid path '" + paths[i]
                                + "' for method " + method.getDeclaringClass().getName() + "#"
                                + method.getName() + ": don't end with more than one '/'");
                    }
                    paths[i] = paths[i].substring(0, paths[i].length() - 1);
                }
            }
        }
        return restMethods;
    }

    private boolean quicklyPass(List<Method> pastMethods, Method method, Class<?> controllerClass) {
        // public, not static, not abstract, @Ignored
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())
                || method.isAnnotationPresent(Ignored.class)) {
            if (logger.isDebugEnabled()) {
                logger.debug("ignores method of controller " + controllerClass.getName() + "."
                        + method.getName() + "  [@ignored?not public?abstract?static?]");
            }
            return true;
        }
        // 刚才在继承类(子类)已经声明的方法，不必重复处理了
        for (Method past : pastMethods) {
            if (past.getName().equals(method.getName())) {
                if (Arrays.equals(past.getParameterTypes(), method.getParameterTypes())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ignoresCommonMethod(Method method) {
        // 来自 Object 的方法
        if (ClassUtils.hasMethod(Object.class, method.getName(), method.getParameterTypes())) {
            return true;
        }

        // 以下可能是一些java bean的方法，这些不需要
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3
                && Character.isUpperCase(name.charAt("get".length()))
                && method.getParameterTypes().length == 0 && method.getReturnType() != void.class) {
            if (null == method.getAnnotation(Get.class)) {
                return true;
            }
        }
        if (name.startsWith("is")
                && name.length() > 3
                && Character.isUpperCase(name.charAt("is".length()))
                && method.getParameterTypes().length == 0
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            if (null == method.getAnnotation(Get.class)) {
                return true;
            }
        }
        if (name.startsWith("set") && name.length() > 3
                && Character.isUpperCase(name.charAt("set".length()))
                && method.getParameterTypes().length == 1 && method.getReturnType() == void.class) {
            if (null == method.getAnnotation(Post.class)) {
                return true;
            }
        }
        return false;
    }

    public void setMappingPaths(String[] mappingPaths) {
        this.mappingPaths = mappingPaths;
    }

    public String[] getMappingPaths() {
        return mappingPaths;
    }

    public Class<?> getControllerClass() {
        return controllerClass;
    }

    public void setControllerClass(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
    }

    public Object getControllerObject() {
        return controllerObject;
    }

    public void setControllerObject(Object controllerObject) {
        this.controllerObject = controllerObject;
    }

    public String getControllerName() {
        return controllerName;
    }

    public void setControllerName(String controllerName) {
        this.controllerName = controllerName;
    }

    @Override
    public String toString() {
        return controllerClass.getName();
    }

}

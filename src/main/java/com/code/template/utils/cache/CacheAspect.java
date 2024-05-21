package com.code.template.utils.cache;

import com.code.template.constants.CodeEnum;
import com.code.template.constants.RedisConstants;
import com.code.template.exception.CustomException;
import com.code.template.utils.RedisUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存方面
 *
 * @author HeXin
 * @date 2024/03/08
 */
@Aspect
@Component
@Slf4j
public class CacheAspect {
    private final RedisUtil redisUtil;

    private final ObjectMapper objectMapper;

    @Autowired
    public CacheAspect(RedisUtil redisUtil, ObjectMapper objectMapper) {
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
    }

    @Pointcut("@annotation(com.code.template.utils.cache.Cache)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object around(ProceedingJoinPoint joinPoint) {
        // 获取方法签名
        Method method = getMethod(joinPoint);

        // 获取实际方法
        Method realMethod = getRealMethod(joinPoint, method);

        Class<?> returnType = method.getReturnType();
        Cache cache = AnnotationUtils.findAnnotation(realMethod, Cache.class);
        // 获取参数值
        Object[] args = joinPoint.getArgs();
        // 获取参数名
        List<String> parameterNames = Arrays.stream(((MethodSignature) joinPoint.getSignature()).getParameterNames()).toList();
        // 获取生命周期
        assert cache != null; // 避免编译器报警告，因为已经赋予了默认值
        long ttl = cache.ttl();
        TimeUnit timeUnit = cache.timeUnit();
        RedisConstants constants = cache.constants();
        String cacheKey = cache.cacheKey();
        // 获取参数映射
        Map<String, Object> paramMap = getParamMap(method, parameterNames, args);

        if (cacheKey.isEmpty()) {
            cacheKey = generateDefaultCacheKey(joinPoint, method);
        }

        String key = generataKey(cacheKey, paramMap, constants == RedisConstants.DEFAULT ? null : constants);

        // 先从缓存中获取
        String json = redisUtil.getString(key);
        if (StringUtils.isNotBlank(json)) {
            log.info("命中缓存 <== key：{}", key);
            try {
                return objectMapper.readValue(json, returnType);
            } catch (JsonProcessingException e) {
                throw new CustomException(e);
            }
        }
        if (json != null) {
            // 空数据
            log.info("命中空数据 <== key：{}", key);
            throw new CustomException(CodeEnum.DATA_NOT_EXIST);
        }
        // 缓存未命中，将数据存入缓存
        Object result;
        try {
            // 获取方法返回结果
            result = joinPoint.proceed();
        } catch (Throwable e) {
            // 防止缓存穿透
            log.info("缓存空数据 ==> key：{}", key);
            redisUtil.set(key, " ", RedisConstants.DEFAULT.getTtl(), RedisConstants.DEFAULT.getTimeUnit());
            throw new CustomException(CodeEnum.DATA_NOT_EXIST);
        }
        if (result == null) {
            // 缓存空数据
            redisUtil.set(key, " ", RedisConstants.DEFAULT.getTtl(), RedisConstants.DEFAULT.getTimeUnit());
            throw new CustomException(CodeEnum.DATA_NOT_EXIST);
        }
        log.info("缓存数据 ==> key：{}", key);
        if (constants == RedisConstants.DEFAULT) {
            redisUtil.set(key, result, ttl, timeUnit);
        } else {
            redisUtil.set(key, result, constants.getTtl(), constants.getTimeUnit());
        }
        return result;
    }

    /**
     * 获取方法签名
     *
     * @param joinPoint 加入点
     * @return {@link Method}
     */
    private Method getMethod(ProceedingJoinPoint joinPoint) {
        Signature signature = joinPoint.getSignature();
        return ((MethodSignature) signature).getMethod();
    }

    /**
     * 获取真实方法，防止接口代理的问题
     *
     * @param joinPoint 加入点
     * @param method    方法
     * @return {@link Method}
     */
    private Method getRealMethod(ProceedingJoinPoint joinPoint, Method method) {
        Method realMethod;
        try {
            realMethod = joinPoint.getTarget().getClass()
                    .getDeclaredMethod(joinPoint.getSignature().getName(), method.getParameterTypes());
        } catch (NoSuchMethodException e) {
            log.error("反射错误");
            throw new CustomException(e);
        }
        return realMethod;
    }

    /**
     * 获取方法参数与对应的 @CacheParam 注解的值
     *
     * @param method 方法
     * @param args   参数
     * @return {@link Map}<{@link String}, {@link Object}>
     */
    private Map<String, Object> getParamMap(Method method, List<String> parameterNames, Object[] args) {
        // 新建映射关系
        Map<String, Object> paramMap = new HashMap<>();
        // 获取参数注解
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        // 获取参数名(转换为List方便匹配)

        for (int i = 0; i < parameterAnnotations.length; i++) {
            // 获取参数名
            String paramName = parameterNames.get(i);
            // 循环匹配
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof CacheParam cacheParam && (!cacheParam.value().isEmpty())) {
                    // 参数名是否与变量名匹配
                    boolean matchFound = parameterNames.contains(cacheParam.value());
                    // 若不匹配则直接抛出异常
                    if (!matchFound) {
                        throw new CustomException("参数名 '" + cacheParam.value() + "‘ 未找到对应匹配的方法参数名！");
                    } else {
                        paramName = cacheParam.value();
                    }

                }
                // 匹配则直接存入
                paramMap.put(paramName, args[i]);
            }
        }

        // 如果即没有配置 cacheKey ，也没有添加 @CacheParam 注解，则会导致 paramMap 为空，提示异常
        if (paramMap.isEmpty()) {
            throw new CustomException("缓存的key未正常配置！");
        }
        return paramMap;
    }

    /**
     * 生成默认的缓存键
     *
     * @param method 方法对象
     * @return 默认的缓存键
     */
    private String generateDefaultCacheKey(ProceedingJoinPoint joinPoint, Method method) {
        StringBuilder patternBuilder = new StringBuilder();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        boolean first = true;
        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof CacheParam cacheParam) {
                    String paramName = cacheParam.value();
                    if (paramName.isEmpty()) {
                        paramName = ((MethodSignature) joinPoint.getSignature()).getParameterNames()[i];
                    }
                    if (!first) {
                        patternBuilder.append(":");
                    }
                    patternBuilder.append("{").append(paramName).append("}");
                    first = false;
                }
            }
        }

        return patternBuilder.toString();
    }

    /**
     * 生成缓存 key
     *
     * @param paramMap  参数映射
     * @param paramKey  参数键
     * @param constants Redis常数
     * @return {@link String}
     */
    private String generataKey(String paramKey, Map<String, Object> paramMap, RedisConstants constants) {
        // 创建一个可变String存储cacheKey
        StringBuilder builder;
        if (constants == null) {
            builder = new StringBuilder();
        } else {
            builder = new StringBuilder(constants.getKey());
        }

        // 是否为第一个key
        boolean isFirst = true;

        // 匹配括号内容的正则表达式
        Pattern pattern = Pattern.compile("\\{(\\w+)}");
        Matcher matcher = pattern.matcher(paramKey);

        while (matcher.find()) {
            // 获取参数名
            String paramName = matcher.group(1);
            // 获取参数值
            Object paramValue = paramMap.get(paramName);
            // 该参数没有对应的值且参数列表并未发现该参数
            if(paramValue == null && !paramMap.containsKey(paramName)) {
                throw new CustomException("参数名 '" + paramName + "' 未找到对应匹配的方法参数名！ ");
            }
            // 拼接key
            if (!isFirst) {
                builder.append(":");
            }
            builder.append(paramValue != null ? paramValue.toString() : "null");
            isFirst = false;
        }

        return builder.toString();
    }
}

package com.netflix.hystrix.strategy.properties;

/**
 * @ExcludeFromJavadoc
 * @author agent
 * 从系统变量中获取 hystrix 的动态属性
 */
public final class HystrixDynamicPropertiesSystemProperties implements HystrixDynamicProperties {
    
    /**
     * Only public for unit test purposes.
     */
    public HystrixDynamicPropertiesSystemProperties() {}

    /**
     * 延迟初始化对象 只有在访问该静态域时 才会创建
     */
    private static class LazyHolder {
        private static final HystrixDynamicPropertiesSystemProperties INSTANCE = new HystrixDynamicPropertiesSystemProperties();
    }
    
    public static HystrixDynamicProperties getInstance() {
        return LazyHolder.INSTANCE;
    }
    
    //TODO probably should not be anonymous classes for GC reasons and possible jit method eliding.
    @Override
    public HystrixDynamicProperty<Integer> getInteger(final String name, final Integer fallback) {
        return new HystrixDynamicProperty<Integer>() {
            
            @Override
            public String getName() {
                return name;
            }

            /**
             * Integer.getInteger  该方法就是从 System.getProp 中获取对应属性  fallback 代表默认值
             * @return
             */
            @Override
            public Integer get() {
                return Integer.getInteger(name, fallback);
            }
            @Override
            public void addCallback(Runnable callback) {
            }
        };
    }

    /**
     * 获取 string 类型的系统变量
     * @param name
     * @param fallback
     * @return
     */
    @Override
    public HystrixDynamicProperty<String> getString(final String name, final String fallback) {
        return new HystrixDynamicProperty<String>() {
            
            @Override
            public String getName() {
                return name;
            }
            
            @Override
            public String get() {
                return System.getProperty(name, fallback);
            }

            @Override
            public void addCallback(Runnable callback) {
            }
        };
    }

    /**
     * 获取long 型的系统变量
     * @param name
     * @param fallback
     * @return
     */
    @Override
    public HystrixDynamicProperty<Long> getLong(final String name, final Long fallback) {
        return new HystrixDynamicProperty<Long>() {
            
            @Override
            public String getName() {
                return name;
            }
            
            @Override
            public Long get() {
                return Long.getLong(name, fallback);
            }
            
            @Override
            public void addCallback(Runnable callback) {
            }
        };
    }

    /**
     * 获取 boolean 类型的 系统变量
     * @param name
     * @param fallback
     * @return
     */
    @Override
    public HystrixDynamicProperty<Boolean> getBoolean(final String name, final Boolean fallback) {
        return new HystrixDynamicProperty<Boolean>() {
            
            @Override
            public String getName() {
                return name;
            }
            @Override
            public Boolean get() {
                if (System.getProperty(name) == null) {
                    return fallback;
                }
                return Boolean.getBoolean(name);
            }
            
            @Override
            public void addCallback(Runnable callback) {
            }
        };
    }
    
}
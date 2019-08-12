package com.netflix.hystrix;

import static com.netflix.hystrix.strategy.properties.HystrixPropertiesChainedProperty.forInteger;

import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;

/**
 * Properties for Hystrix timer thread pool.
 * <p>
 * Default implementation of methods uses Archaius (https://github.com/Netflix/archaius)
 * hystrix 定时线程池 的相关配置
 */
public abstract class HystrixTimerThreadPoolProperties {

    /**
     * 核心线程数
     */
    private final HystrixProperty<Integer> corePoolSize;

    /**
     * 初始化时 使用cpu核数 作为coreSize
     */
    protected HystrixTimerThreadPoolProperties() {
        this(new Setter().withCoreSize(Runtime.getRuntime().availableProcessors()));
    }

    protected HystrixTimerThreadPoolProperties(Setter setter) {
        this.corePoolSize = getProperty("hystrix", "coreSize", setter.getCoreSize());
    }

    private static HystrixProperty<Integer> getProperty(String propertyPrefix, String instanceProperty, Integer defaultValue) {
        
        return forInteger()
                .add(propertyPrefix + ".timer.threadpool.default." + instanceProperty, defaultValue)
                .build();
    }

    public HystrixProperty<Integer> getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Factory method to retrieve the default Setter.
     */
    public static Setter Setter() {
        return new Setter();
    }

    /**
     * Fluent interface that allows chained setting of properties.
     * <p>
     * See {@link HystrixPropertiesStrategy} for more information on order of precedence.
     * <p>
     * Example:
     * <p>
     * <pre> {@code
     * HystrixTimerThreadPoolProperties.Setter()
     *           .withCoreSize(10);
     * } </pre>
     *
     * @NotThreadSafe
     */
    public static class Setter {
        private Integer coreSize = null;

        private Setter() {
        }

        public Integer getCoreSize() {
            return coreSize;
        }

        public Setter withCoreSize(int value) {
            this.coreSize = value;
            return this;
        }
    }
}

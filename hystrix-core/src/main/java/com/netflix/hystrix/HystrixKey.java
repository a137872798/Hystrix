package com.netflix.hystrix;

/**
 * Basic class for hystrix keys
 * 熔断器 键
 */
public interface HystrixKey {
    /**
     * The word 'name' is used instead of 'key' so that Enums can implement this interface and it work natively.
     *
     * @return String
     * 使得enum 可以实现这个接口 因为 枚举类 有实现该接口
     */
    String name();

    /**
     * Default implementation of the interface
     * 默认实现 就是设置了可以获取name 的相关方法
     */
    abstract class HystrixKeyDefault implements HystrixKey {
        private final String name;

        public HystrixKeyDefault(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

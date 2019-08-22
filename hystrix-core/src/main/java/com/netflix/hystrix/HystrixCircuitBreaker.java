/**
 * Copyright 2012 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import rx.Subscriber;
import rx.Subscription;

/**
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 * 断路器对象
 */
public interface HystrixCircuitBreaker {

    /**
     * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
     * not modify any internal state, and takes into account the half-open logic which allows some requests through
     * after the circuit has been opened
     *
     * @return boolean whether a request should be permitted
     * 每个请求 发出时 会调用该方法判断 是否被允许
     */
    boolean allowRequest();

    /**
     * Whether the circuit is currently open (tripped).
     *
     * @return boolean state of circuit breaker
     * 断路器是否被打开
     */
    boolean isOpen();

    /**
     * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     * 当半开状态时 成功后 会调用该方法 (应该是恢复开放状态之类的)
     */
    void markSuccess();

    /**
     * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
     * 半开状态时 未成功标记成 未成功
     */
    void markNonSuccess();

    /**
     * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
     * state.
     * 当命令开始执行时 会调用 是 非幂等的方法
     */
    boolean attemptExecution();

    /**
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    class Factory {
        // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
        // 针对 断路器的缓存对象
        private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

        /**
         * 根据 commandkey 生成对应的 断路器对象 当每个 commandkey 对应到一个断路器对象时 是线程安全的
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
         * <p>
         * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
         *
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @param group
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param properties
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @param metrics
         *            Pass-thru to {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key, HystrixCommandGroupKey group, HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // this should find it for all but the first time
            // 首先尝试从缓存中获取对象
            HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
            if (previouslyCached != null) {
                return previouslyCached;
            }

            // if we get here this is the first time so we need to initialize

            // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
            // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
            // If 2 threads hit here only one will get added and the other will get a non-null response instead.
            // 初始化一个新的对象并存入缓存中
            HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand.putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
            if (cbForCommand == null) {
                // this means the putIfAbsent step just created a new one so let's retrieve and return it
                return circuitBreakersByCommand.get(key.name());
            } else {
                // this means a race occurred and while attempting to 'put' another one got there before
                // and we instead retrieved it and will now return it
                return cbForCommand;
            }
        }

        /**
         * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
         *
         * @param key
         *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
         * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
         * 尝试从缓存中 获取断路器对象
         */
        public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
            return circuitBreakersByCommand.get(key.name());
        }

        /**
         * Clears all circuit breakers. If new requests come in instances will be recreated.
         * 清空缓存
         */
        /* package */
        static void reset() {
            circuitBreakersByCommand.clear();
        }
    }


    /**
     * The default production implementation of {@link HystrixCircuitBreaker}.
     * 默认的断路器实现  每个实现对应到一个 command 对象
     *
     * @ExcludeFromJavadoc
     * @ThreadSafe
     */
    /* package */class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {
        /**
         *  command 相关属性 包含默认值
         */
        private final HystrixCommandProperties properties;
        /**
         * 命令相关的测量对象 该对象是由外部传来的
         */
        private final HystrixCommandMetrics metrics;

        /**
         * 断路器状态
         */
        enum Status {
            /**
             * 当前属于关闭状态
             */
            CLOSED,
            /**
             * 打开
             */
            OPEN,
            /**
             * 半关闭状态
             */
            HALF_OPEN;
        }

        /**
         * 断路器默认属于关闭状态 代表不阻拦任何请求
         */
        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);
        /**
         * 断路器 是否被打开
         */
        private final AtomicLong circuitOpened = new AtomicLong(-1);
        /**
         * 订阅者对象
         */
        private final AtomicReference<Subscription> activeSubscription = new AtomicReference<Subscription>(null);

        /**
         * 熔断器 对象 使用  commandKey  commandGroupKey metrics 进行初始化
         * @param key
         * @param commandGroup
         * @param properties
         * @param metrics
         */
        protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, final HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
            // 设置 hustrix 的 属性和 测量对象
            this.properties = properties;
            this.metrics = metrics;

            //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
            // 生成了一个订阅者
            Subscription s = subscribeToStream();
            // 将订阅者设置到原子引用中
            activeSubscription.set(s);
        }

        /**
         * 生成订阅者 对象  用于 订阅 metrics 对象 获取 commandComplete/commandStart/.. 事件发出的数据 或者
         * @return
         */
        private Subscription subscribeToStream() {
            /*
             * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
             * metrics 内部 维护了 各种 数据流 (基于rxjava 的observable 对象)  这里 获取到需要观察的流 并根据 成功 次数 等 判断是否要 熔断
             */
            return metrics.getHealthCountsStream()
                    // 获取健康计数的 数据流 该数据以 一个 roll 为单位
                    .observe()
                    // 设置订阅者对象
                    .subscribe(new Subscriber<HealthCounts>() {
                        @Override
                        public void onCompleted() {

                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        /**
                         * 处理收到的 stream 中的数据  HealthCountsStream 将每个bucket 内数据 累加到 HealthCounts 上  每当累计满一个  roll 的数据后 会触发onNext
                         * @param hc
                         */
                        @Override
                        public void onNext(HealthCounts hc) {
                            // check if we are past the statisticalWindowVolumeThreshold
                            // 如果请求数小于 触发熔断的请求数 不做任何处理
                            if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
                                // we are not past the minimum volume threshold for the stat window,
                                // so no change to circuit status.
                                // if it was CLOSED, it stays CLOSED
                                // if it was half-open, we need to wait for a successful command execution
                                // if it was open, we need to wait for sleep window to elapse
                            } else {
                                // 这里开始 判断是否需要熔断 如果 失败比率 低于 熔断 比率 不进行处理
                                if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage().get()) {
                                    //we are not past the minimum error threshold for the stat window,
                                    // so no change to circuit status.
                                    // if it was CLOSED, it stays CLOSED
                                    // if it was half-open, we need to wait for a successful command execution
                                    // if it was open, we need to wait for sleep window to elapse
                                } else {
                                    // our failure rate is too high, we need to set the state to OPEN
                                    // 开启熔断器 这里如果 是半开状态 无视收到的数据
                                    if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                                        // 设置熔断器触发时间
                                        circuitOpened.set(System.currentTimeMillis());
                                    }
                                }
                            }
                        }
                    });
        }

        /**
         * 标记成功状态  代表熔断器 需要从 半开模式 变成关闭模式
         */
        @Override
        public void markSuccess() {
            // 如果是半开状态的话 才能被 设置成关闭
            if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
                //This thread wins the race to close the circuit - it resets the stream to start it over from 0
                // 重置之前统计的 数据
                metrics.resetStream();
                // 在重置的情况下 当前维护的订阅者 也要关闭
                Subscription previousSubscription = activeSubscription.get();
                if (previousSubscription != null) {
                    previousSubscription.unsubscribe();
                }
                // 生成一个新的订阅者对象 并设置
                Subscription newSubscription = subscribeToStream();
                activeSubscription.set(newSubscription);
                // 重置熔断时间
                circuitOpened.set(-1L);
            }
        }

        /**
         * 将 半开模式 重新变成 开放模式
         */
        @Override
        public void markNonSuccess() {
            if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
                //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
                circuitOpened.set(System.currentTimeMillis());
            }
        }

        /**
         * 判断熔断器是否打开
         * @return
         */
        @Override
        public boolean isOpen() {
            // 如果时 强制打开 熔断器的话 返回true
            if (properties.circuitBreakerForceOpen().get()) {
                return true;
            }
            // 同上
            if (properties.circuitBreakerForceClosed().get()) {
                return false;
            }
            // 判断熔断时间
            return circuitOpened.get() >= 0;
        }

        /**
         * 是否允许接受请求
         * @return
         */
        @Override
        public boolean allowRequest() {
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                // 判断当前是否处于 半开状态 是 就禁止请求
                if (status.get().equals(Status.HALF_OPEN)) {
                    return false;
                } else {
                    // 是否在沉睡窗口 如果当前时间 在上次断路时间 + 沉睡时间之上的 话 就允许接受请求 这里应该 会将 OPEN -> HALF_OPEN
                    return isAfterSleepWindow();
                }
            }
        }

        /**
         * 是否 在 沉睡窗口
         * @return
         */
        private boolean isAfterSleepWindow() {
            final long circuitOpenTime = circuitOpened.get();
            final long currentTime = System.currentTimeMillis();
            // 获取熔断器沉睡时间
            final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
            return currentTime > circuitOpenTime + sleepWindowTime;
        }

        /**
         * 尝试执行
         * @return
         */
        @Override
        public boolean attemptExecution() {
            if (properties.circuitBreakerForceOpen().get()) {
                return false;
            }
            if (properties.circuitBreakerForceClosed().get()) {
                return true;
            }
            if (circuitOpened.get() == -1) {
                return true;
            } else {
                // 如果 超过沉睡时间
                if (isAfterSleepWindow()) {
                    //only the first request after sleep window should execute
                    //if the executing command succeeds, the status will transition to CLOSED
                    //if the executing command fails, the status will transition to OPEN
                    //if the executing command gets unsubscribed, the status will transition to OPEN
                    // 修改成半开状态 之后如果 成功 应该就是 调用 markSuccess 修改成 打开 否则 修改成 关闭
                    if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }
    }

    /**
     * An implementation of the circuit breaker that does nothing.
     * 空的 熔断器 对象 总是允许接受请求  处于Close 状态
     *
     * @ExcludeFromJavadoc
     */
    /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void markSuccess() {

        }

        @Override
        public void markNonSuccess() {

        }

        @Override
        public boolean attemptExecution() {
            return true;
        }
    }

}

/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.metric.HystrixCommandCompletion;
import com.netflix.hystrix.metric.consumer.CumulativeThreadPoolEventCounterStream;
import com.netflix.hystrix.metric.consumer.RollingThreadPoolMaxConcurrencyStream;
import com.netflix.hystrix.metric.consumer.RollingThreadPoolEventCounterStream;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.functions.Func0;
import rx.functions.Func2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有关线程池的 计量对象
 * Used by {@link HystrixThreadPool} to record metrics.
 */
public class HystrixThreadPoolMetrics extends HystrixMetrics {

    /**
     * 内部存放了所有的 hystrix 事件类型
     */
    private static final HystrixEventType[] ALL_COMMAND_EVENT_TYPES = HystrixEventType.values();
    /**
     * 获取线程池相关的事件类型
     */
    private static final HystrixEventType.ThreadPool[] ALL_THREADPOOL_EVENT_TYPES = HystrixEventType.ThreadPool.values();
    /**
     * 获取线程池相关事件数量
     */
    private static final int NUMBER_THREADPOOL_EVENT_TYPES = ALL_THREADPOOL_EVENT_TYPES.length;

    // String is HystrixThreadPoolKey.name() (we can't use HystrixThreadPoolKey directly as we can't guarantee it implements hashcode/equals correctly)
    /**
     * 同样使用一个 静态变量当作缓存容器 每次使用 key 去获取时 没有就会将元素生成并存放到缓存中
     */
    private static final ConcurrentHashMap<String, HystrixThreadPoolMetrics> metrics = new ConcurrentHashMap<String, HystrixThreadPoolMetrics>();

    /**
     * Get or create the {@link HystrixThreadPoolMetrics} instance for a given {@link HystrixThreadPoolKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HystrixThreadPoolMetrics} per {@link HystrixThreadPoolKey}.
     * 
     * @param key
     *            {@link HystrixThreadPoolKey} of {@link HystrixThreadPool} instance requesting the {@link HystrixThreadPoolMetrics}
     * @param threadPool
     *            Pass-thru of ThreadPoolExecutor to {@link HystrixThreadPoolMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HystrixThreadPoolMetrics} instance on first time when constructed
     * @return {@link HystrixThreadPoolMetrics}
     * 使用key 去获取 线程池 测量对象
     */
    public static HystrixThreadPoolMetrics getInstance(HystrixThreadPoolKey key, ThreadPoolExecutor threadPool, HystrixThreadPoolProperties properties) {
        // attempt to retrieve from cache first
        // 尝试从缓存中获取数据对象
        HystrixThreadPoolMetrics threadPoolMetrics = metrics.get(key.name());
        if (threadPoolMetrics != null) {
            return threadPoolMetrics;
        } else {
            // 使用内置锁 保证只有单对象创建成功
            synchronized (HystrixThreadPoolMetrics.class) {
                HystrixThreadPoolMetrics existingMetrics = metrics.get(key.name());
                if (existingMetrics != null) {
                    return existingMetrics;
                } else {
                    // 初始化线程池相关的测量对象
                    HystrixThreadPoolMetrics newThreadPoolMetrics = new HystrixThreadPoolMetrics(key, threadPool, properties);
                    metrics.putIfAbsent(key.name(), newThreadPoolMetrics);
                    return newThreadPoolMetrics;
                }
            }
        }
    }

    /**
     * Get the {@link HystrixThreadPoolMetrics} instance for a given {@link HystrixThreadPoolKey} or null if one does not exist.
     * 
     * @param key
     *            {@link HystrixThreadPoolKey} of {@link HystrixThreadPool} instance requesting the {@link HystrixThreadPoolMetrics}
     * @return {@link HystrixThreadPoolMetrics}
     * 根据 缓存键 获取对应的 测量对象
     */
    public static HystrixThreadPoolMetrics getInstance(HystrixThreadPoolKey key) {
        return metrics.get(key.name());
    }

    /**
     * All registered instances of {@link HystrixThreadPoolMetrics}
     * 
     * @return {@code Collection<HystrixThreadPoolMetrics>}
     * 这里返回的是 当前缓存中维护的所有视图对象
     */
    public static Collection<HystrixThreadPoolMetrics> getInstances() {
        List<HystrixThreadPoolMetrics> threadPoolMetrics = new ArrayList<HystrixThreadPoolMetrics>();
        // 获取所有线程池 测量对象
        for (HystrixThreadPoolMetrics tpm: metrics.values()) {
            // 只返回完成过任务的线程池对象
            if (hasExecutedCommandsOnThread(tpm)) {
                threadPoolMetrics.add(tpm);
            }
        }

        return Collections.unmodifiableCollection(threadPoolMetrics);
    }

    /**
     * 判断当前线程是否完成过任务
     * @param threadPoolMetrics
     * @return
     */
    private static boolean hasExecutedCommandsOnThread(HystrixThreadPoolMetrics threadPoolMetrics) {
        return threadPoolMetrics.getCurrentCompletedTaskCount().intValue() > 0;
    }

    /**
     * 将线程池相关的事件 数据累加到 bucket 数组中
     */
    public static final Func2<long[], HystrixCommandCompletion, long[]> appendEventToBucket
            = new Func2<long[], HystrixCommandCompletion, long[]>() {
        @Override
        public long[] call(long[] initialCountArray, HystrixCommandCompletion execution) {
            ExecutionResult.EventCounts eventCounts = execution.getEventCounts();
            for (HystrixEventType eventType: ALL_COMMAND_EVENT_TYPES) {
                long eventCount = eventCounts.getCount(eventType);
                // 因为 commandCompletion 中包含了所有事件类型的结果 这里只需要过滤出 线程池相关的事件
                HystrixEventType.ThreadPool threadPoolEventType = HystrixEventType.ThreadPool.from(eventType);
                if (threadPoolEventType != null) {
                    initialCountArray[threadPoolEventType.ordinal()] += eventCount;
                }
            }
            return initialCountArray;
        }
    };

    /**
     * 针对 ThreadPool 的 事件累加器
     */
    public static final Func2<long[], long[], long[]> counterAggregator = new Func2<long[], long[], long[]>() {
        @Override
        public long[] call(long[] cumulativeEvents, long[] bucketEventCounts) {
            for (int i = 0; i < NUMBER_THREADPOOL_EVENT_TYPES; i++) {
                cumulativeEvents[i] += bucketEventCounts[i];
            }
            return cumulativeEvents;
        }
    };

    /**
     * Clears all state from metrics. If new requests come in instances will be recreated and metrics started from scratch.
     *
     */
    /* package */ static void reset() {
        metrics.clear();
    }

    /**
     * 线程缓存键 对象
     */
    private final HystrixThreadPoolKey threadPoolKey;
    /**
     * 被统计的 线程池 对象
     */
    private final ThreadPoolExecutor threadPool;
    /**
     * 线程池相关的 属性对象
     */
    private final HystrixThreadPoolProperties properties;

    /**
     * 当前执行的任务数  每当线程池拒绝一个任务时 减少该计数值
     */
    private final AtomicInteger concurrentExecutionCount = new AtomicInteger();

    /**
     * 滚动线程池 事件计数流
     */
    private final RollingThreadPoolEventCounterStream rollingCounterStream;
    /**
     * 累加线程池 事件计数流
     */
    private final CumulativeThreadPoolEventCounterStream cumulativeCounterStream;
    /**
     * 滚动线程池 最大并发流
     */
    private final RollingThreadPoolMaxConcurrencyStream rollingThreadPoolMaxConcurrencyStream;

    /**
     * 初始化 线程池对象
     * @param threadPoolKey
     * @param threadPool
     * @param properties
     */
    private HystrixThreadPoolMetrics(HystrixThreadPoolKey threadPoolKey, ThreadPoolExecutor threadPool, HystrixThreadPoolProperties properties) {
        // 父类 本来内部是维护了一个 counter 对象 (包含了一个bucket 对象)  这里没有选择传入 并重写了 父类使用counter 的相关方法
        super(null);
        this.threadPoolKey = threadPoolKey;
        this.threadPool = threadPool;
        this.properties = properties;

        // 调用静态方法 并生成对应的 数据流对象  以画卷为单位
        rollingCounterStream = RollingThreadPoolEventCounterStream.getInstance(threadPoolKey, properties);
        // 以 bucket 为单位
        cumulativeCounterStream = CumulativeThreadPoolEventCounterStream.getInstance(threadPoolKey, properties);
        // 以 roll 为单位(实际 还是一个bucket 只是填充了 bucket) 获取 最大的 并发值  也就是说 该统计的 意义是 最近的一段时间内的最大并发数
        rollingThreadPoolMaxConcurrencyStream = RollingThreadPoolMaxConcurrencyStream.getInstance(threadPoolKey, properties);
    }

    /**
     * {@link ThreadPoolExecutor} this executor represents.
     *
     * @return ThreadPoolExecutor
     */
    public ThreadPoolExecutor getThreadPool() {
        return threadPool;
    }

    /**
     * {@link HystrixThreadPoolKey} these metrics represent.
     * 
     * @return HystrixThreadPoolKey
     */
    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /**
     * {@link HystrixThreadPoolProperties} of the {@link HystrixThreadPool} these metrics represent.
     * 
     * @return HystrixThreadPoolProperties
     */
    public HystrixThreadPoolProperties getProperties() {
        return properties;
    }

    /**
     * Value from {@link ThreadPoolExecutor#getActiveCount()}
     * 
     * @return Number
     * 返回当前等待处理的 work
     */
    public Number getCurrentActiveCount() {
        return threadPool.getActiveCount();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getCompletedTaskCount()}
     * 
     * @return Number
     * 线程池本身包含 记录完成任务数量的 api
     */
    public Number getCurrentCompletedTaskCount() {
        return threadPool.getCompletedTaskCount();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getCorePoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentCorePoolSize() {
        return threadPool.getCorePoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getLargestPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentLargestPoolSize() {
        return threadPool.getLargestPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getMaximumPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentMaximumPoolSize() {
        return threadPool.getMaximumPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getPoolSize()}
     * 
     * @return Number
     */
    public Number getCurrentPoolSize() {
        return threadPool.getPoolSize();
    }

    /**
     * Value from {@link ThreadPoolExecutor#getTaskCount()}
     * 
     * @return Number
     */
    public Number getCurrentTaskCount() {
        return threadPool.getTaskCount();
    }

    /**
     * Current size of {@link BlockingQueue} used by the thread-pool
     * 
     * @return Number
     */
    public Number getCurrentQueueSize() {
        return threadPool.getQueue().size();
    }

    // 以上都是线程池相关的属性 重点是下面的 统计数据 怎么来

    /**
     * Invoked each time a thread is executed.
     */
    public void markThreadExecution() {
        concurrentExecutionCount.incrementAndGet();
    }

    /**
     * Rolling count of number of threads executed during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     *
     * @return rolling count of threads executed
     * 获取 滚动执行数量 rollingCounterStream 是 什么时候 统计到数据的
     */
    public long getRollingCountThreadsExecuted() {
        return rollingCounterStream.getLatestCount(HystrixEventType.ThreadPool.EXECUTED);
    }

    /**
     * Cumulative count of number of threads executed since the start of the application.
     * 
     * @return cumulative count of threads executed
     * 获取累加值
     */
    public long getCumulativeCountThreadsExecuted() {
        return cumulativeCounterStream.getLatestCount(HystrixEventType.ThreadPool.EXECUTED);
    }

    /**
     * Rolling count of number of threads rejected during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     *
     * @return rolling count of threads rejected
     */
    public long getRollingCountThreadsRejected() {
        return rollingCounterStream.getLatestCount(HystrixEventType.ThreadPool.REJECTED);
    }

    /**
     * Cumulative count of number of threads rejected since the start of the application.
     *
     * @return cumulative count of threads rejected
     */
    public long getCumulativeCountThreadsRejected() {
        return cumulativeCounterStream.getLatestCount(HystrixEventType.ThreadPool.REJECTED);
    }

    public long getRollingCount(HystrixEventType.ThreadPool event) {
        return rollingCounterStream.getLatestCount(event);
    }

    public long getCumulativeCount(HystrixEventType.ThreadPool event) {
        return cumulativeCounterStream.getLatestCount(event);
    }

    @Override
    public long getCumulativeCount(HystrixRollingNumberEvent event) {
        return cumulativeCounterStream.getLatestCount(HystrixEventType.ThreadPool.from(event));
    }

    @Override
    public long getRollingCount(HystrixRollingNumberEvent event) {
        return rollingCounterStream.getLatestCount(HystrixEventType.ThreadPool.from(event));
    }

    /**
     * Invoked each time a thread completes.
     */
    public void markThreadCompletion() {
        concurrentExecutionCount.decrementAndGet();
    }

    /**
     * Rolling max number of active threads during rolling statistical window.
     * <p>
     * The rolling window is defined by {@link HystrixThreadPoolProperties#metricsRollingStatisticalWindowInMilliseconds()}.
     * 
     * @return rolling max active threads
     */
    public long getRollingMaxActiveThreads() {
        return rollingThreadPoolMaxConcurrencyStream.getLatestRollingMax();
    }

    /**
     * Invoked each time a command is rejected from the thread-pool
     */
    public void markThreadRejection() {
        concurrentExecutionCount.decrementAndGet();
    }

    public static Func0<Integer> getCurrentConcurrencyThunk(final HystrixThreadPoolKey threadPoolKey) {
        return new Func0<Integer>() {
            @Override
            public Integer call() {
                return HystrixThreadPoolMetrics.getInstance(threadPoolKey).concurrentExecutionCount.get();
            }
        };
    }
}
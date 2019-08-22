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
import com.netflix.hystrix.metric.HystrixThreadEventStream;
import com.netflix.hystrix.metric.consumer.CumulativeCommandEventCounterStream;
import com.netflix.hystrix.metric.consumer.HealthCountsStream;
import com.netflix.hystrix.metric.consumer.RollingCommandEventCounterStream;
import com.netflix.hystrix.metric.consumer.RollingCommandLatencyDistributionStream;
import com.netflix.hystrix.metric.consumer.RollingCommandMaxConcurrencyStream;
import com.netflix.hystrix.metric.consumer.RollingCommandUserLatencyDistributionStream;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.functions.Func0;
import rx.functions.Func2;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used by {@link HystrixCommand} to record metrics.
 * 以 command 为 基本单位的统计对象 内部还维护了 更细粒度的 统计对象
 */
public class HystrixCommandMetrics extends HystrixMetrics {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(HystrixCommandMetrics.class);

    /**
     * 维护了所有的事件类型
     */
    private static final HystrixEventType[] ALL_EVENT_TYPES = HystrixEventType.values();

    /**
     * 第二个参数为 CommandCompletion 代表 该数据是在 某个任务执行完成时 统计的 这里的执行完成 应该是包含抛出异常的 (因为 HealthCount 也要统计失败比率)
     */
    public static final Func2<long[], HystrixCommandCompletion, long[]> appendEventToBucket = new Func2<long[], HystrixCommandCompletion, long[]>() {

        // 将本次 command 的执行  结果设置到 初始 bucket 中 并返回 累加数据后的 bucket
        @Override
        public long[] call(long[] initialCountArray, HystrixCommandCompletion execution) {
            // 获取事件计数器  本次执行中 产生的 数据 都会填充到 eventCount 中
            ExecutionResult.EventCounts eventCounts = execution.getEventCounts();
            for (HystrixEventType eventType: ALL_EVENT_TYPES) {
                switch (eventType) {
                    // 一旦出现抛出异常的 跳过本次统计
                    case EXCEPTION_THROWN: break; //this is just a sum of other anyway - don't do the work here
                    default:
                        // 就是将本次 command 的 eventCounts 读取出来的值 设置到 bucket中
                        initialCountArray[eventType.ordinal()] += eventCounts.getCount(eventType);
                        break;
                }
            }
            return initialCountArray;
        }
    };

    /**
     * 桶 聚合???  注意这里入参是  2个 long[]
     */
    public static final Func2<long[], long[], long[]> bucketAggregator = new Func2<long[], long[], long[]>() {
        @Override
        public long[] call(long[] cumulativeEvents, long[] bucketEventCounts) {
            for (HystrixEventType eventType: ALL_EVENT_TYPES) {
                switch (eventType) {
                    // 这里遇到异常时 也要处理
                    case EXCEPTION_THROWN:
                        // 获取所有异常事件  将 第二个事件对象的数据累加到第一个事件对象上
                        // 双层循环 ??? 每遇到一个 异常 把所有异常 又加了一次???
                        for (HystrixEventType exceptionEventType: HystrixEventType.EXCEPTION_PRODUCING_EVENT_TYPES) {
                            cumulativeEvents[eventType.ordinal()] += bucketEventCounts[exceptionEventType.ordinal()];
                        }
                        break;
                    default:
                        // 累加对应的枚举值
                        cumulativeEvents[eventType.ordinal()] += bucketEventCounts[eventType.ordinal()];
                        break;
                }
            }
            return cumulativeEvents;
        }
    };

    // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
    // 缓存了每个命令相关的 统计对象
    private static final ConcurrentHashMap<String, HystrixCommandMetrics> metrics = new ConcurrentHashMap<String, HystrixCommandMetrics>();

    /**
     * Get or create the {@link HystrixCommandMetrics} instance for a given {@link HystrixCommandKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HystrixCommandMetrics} per {@link HystrixCommandKey}.
     * 
     * @param key
     *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCommandMetrics}
     * @param commandGroup
     *            Pass-thru to {@link HystrixCommandMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HystrixCommandMetrics} instance on first time when constructed
     * @return {@link HystrixCommandMetrics}
     * 通过几个缓存键对象 去获取对应的统计对象
     */
    public static HystrixCommandMetrics getInstance(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, HystrixCommandProperties properties) {
        return getInstance(key, commandGroup, null, properties);
    }

    /**
     * Get or create the {@link HystrixCommandMetrics} instance for a given {@link HystrixCommandKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HystrixCommandMetrics} per {@link HystrixCommandKey}.
     *
     * @param key
     *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCommandMetrics}
     * @param commandGroup
     *            Pass-thru to {@link HystrixCommandMetrics} instance on first time when constructed
     * @param properties
     *            Pass-thru to {@link HystrixCommandMetrics} instance on first time when constructed
     * @return {@link HystrixCommandMetrics}
     * 获取 统计对象
     */
    public static HystrixCommandMetrics getInstance(HystrixCommandKey key, HystrixCommandGroupKey commandGroup, HystrixThreadPoolKey threadPoolKey, HystrixCommandProperties properties) {
        // attempt to retrieve from cache first
        // 首先尝试 根据 commandKey.name 从缓存中获取 metrics 对象
        HystrixCommandMetrics commandMetrics = metrics.get(key.name());
        if (commandMetrics != null) {
            return commandMetrics;
        } else {
            synchronized (HystrixCommandMetrics.class) {
                HystrixCommandMetrics existingMetrics = metrics.get(key.name());
                if (existingMetrics != null) {
                    return existingMetrics;
                } else {
                    // 生成新对象并保存到缓存中
                    HystrixThreadPoolKey nonNullThreadPoolKey;
                    if (threadPoolKey == null) {
                        // asKey 就是生成一个 HystrixThreadPoolKeyDefault 对象 且 内部的 name 属性就是这个name
                        nonNullThreadPoolKey = HystrixThreadPoolKey.Factory.asKey(commandGroup.name());
                    } else {
                        nonNullThreadPoolKey = threadPoolKey;
                    }
                    // 通过传入的 属性来生成 metrics
                    HystrixCommandMetrics newCommandMetrics = new HystrixCommandMetrics(key, commandGroup, nonNullThreadPoolKey, properties, HystrixPlugins.getInstance().getEventNotifier());
                    metrics.putIfAbsent(key.name(), newCommandMetrics);
                    return newCommandMetrics;
                }
            }
        }
    }

    /**
     * Get the {@link HystrixCommandMetrics} instance for a given {@link HystrixCommandKey} or null if one does not exist.
     * 
     * @param key
     *            {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCommandMetrics}
     * @return {@link HystrixCommandMetrics}
     * 直接从缓存中获取 对象 因为没有传入 生成对象需要的必备参数 所有  就不尝试生成对象了
     */
    public static HystrixCommandMetrics getInstance(HystrixCommandKey key) {
        return metrics.get(key.name());
    }

    /**
     * All registered instances of {@link HystrixCommandMetrics}
     * 
     * @return {@code Collection<HystrixCommandMetrics>}
     * 返回缓存的视图对象
     */
    public static Collection<HystrixCommandMetrics> getInstances() {
        return Collections.unmodifiableCollection(metrics.values());
    }

    /**
     * Clears all state from metrics. If new requests come in instances will be recreated and metrics started from scratch.
     * 清除统计数据
     */
    /* package */ static void reset() {
        for (HystrixCommandMetrics metricsInstance: getInstances()) {
            metricsInstance.unsubscribeAll();
        }
        metrics.clear();
    }

    /**
     * command 的相关属性
     */
    private final HystrixCommandProperties properties;
    /**
     * 该 command 的 唯一标识
     */
    private final HystrixCommandKey key;
    /**
     * 该 command 所在group 的标识
     */
    private final HystrixCommandGroupKey group;
    /**
     * 线程池键
     */
    private final HystrixThreadPoolKey threadPoolKey;
    /**
     * 当前执行数量
     */
    private final AtomicInteger concurrentExecutionCount = new AtomicInteger();

    /**
     * 健康计数流 熔断器 根据执行的command 的 健康状况来判断是否需要进行熔断
     */
    private HealthCountsStream healthCountsStream;
    /**
     * 事件计数器
     */
    private final RollingCommandEventCounterStream rollingCommandEventCounterStream;
    /**
     * 命令事件计数流 (累加)
     */
    private final CumulativeCommandEventCounterStream cumulativeCommandEventCounterStream;
    private final RollingCommandLatencyDistributionStream rollingCommandLatencyDistributionStream;
    private final RollingCommandUserLatencyDistributionStream rollingCommandUserLatencyDistributionStream;
    /**
     * 最大并发数流
     */
    private final RollingCommandMaxConcurrencyStream rollingCommandMaxConcurrencyStream;

    /**
     * 初始化 统计数据的对象
     * @param key  用于标识 本 统计数据对象 观察的是 哪个 command
     * @param commandGroup  代表本command 属于哪个 commandGroup
     * @param threadPoolKey
     * @param properties
     * @param eventNotifier 默认情况下 外部没有设置 会使用一个空的监听器对象
     */
    /* package */HystrixCommandMetrics(final HystrixCommandKey key, HystrixCommandGroupKey commandGroup, HystrixThreadPoolKey threadPoolKey, HystrixCommandProperties properties, HystrixEventNotifier eventNotifier) {
        super(null);
        // 设置 该 统计对象 是针对 哪个 command 的
        this.key = key;
        this.group = commandGroup;
        this.threadPoolKey = threadPoolKey;
        this.properties = properties;

        // 初始化健康流数据对象
        healthCountsStream = HealthCountsStream.getInstance(key, properties);
        rollingCommandEventCounterStream = RollingCommandEventCounterStream.getInstance(key, properties);
        cumulativeCommandEventCounterStream = CumulativeCommandEventCounterStream.getInstance(key, properties);

        rollingCommandLatencyDistributionStream = RollingCommandLatencyDistributionStream.getInstance(key, properties);
        rollingCommandUserLatencyDistributionStream = RollingCommandUserLatencyDistributionStream.getInstance(key, properties);
        rollingCommandMaxConcurrencyStream = RollingCommandMaxConcurrencyStream.getInstance(key, properties);
    }

    /**
     * 当熔断器 从 半开状态变为 关闭时 触发
     */
    /* package */ synchronized void resetStream() {
        // 清除当前的 订阅者
        healthCountsStream.unsubscribe();
        HealthCountsStream.removeByKey(key);
        // getInstance 会 重新 给 内部的 静态变量 设置对象  使用 内部的静态变量维护关系是为了实现 全局单例 该对象在生成时 会自动设置一个订阅者对象
        healthCountsStream = HealthCountsStream.getInstance(key, properties);
    }

    /**
     * {@link HystrixCommandKey} these metrics represent.
     * 
     * @return HystrixCommandKey
     */
    public HystrixCommandKey getCommandKey() {
        return key;
    }

    /**
     * {@link HystrixCommandGroupKey} of the {@link HystrixCommand} these metrics represent.
     *
     * @return HystrixCommandGroupKey
     */
    public HystrixCommandGroupKey getCommandGroup() {
        return group;
    }

    /**
     * {@link HystrixThreadPoolKey} used by {@link HystrixCommand} these metrics represent.
     *
     * @return HystrixThreadPoolKey
     */
    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /**
     * {@link HystrixCommandProperties} of the {@link HystrixCommand} these metrics represent.
     * 
     * @return HystrixCommandProperties
     */
    public HystrixCommandProperties getProperties() {
        return properties;
    }

    public long getRollingCount(HystrixEventType eventType) {
        return rollingCommandEventCounterStream.getLatest(eventType);
    }

    public long getCumulativeCount(HystrixEventType eventType) {
        return cumulativeCommandEventCounterStream.getLatest(eventType);
    }

    @Override
    public long getCumulativeCount(HystrixRollingNumberEvent event) {
        return getCumulativeCount(HystrixEventType.from(event));
    }

    @Override
    public long getRollingCount(HystrixRollingNumberEvent event) {
        return getRollingCount(HystrixEventType.from(event));
    }

    /**
     * Retrieve the execution time (in milliseconds) for the {@link HystrixCommand#run()} method being invoked at a given percentile.
     * <p>
     * Percentile capture and calculation is configured via {@link HystrixCommandProperties#metricsRollingStatisticalWindowInMilliseconds()} and other related properties.
     * 
     * @param percentile
     *            Percentile such as 50, 99, or 99.5.
     * @return int time in milliseconds
     */
    public int getExecutionTimePercentile(double percentile) {
        return rollingCommandLatencyDistributionStream.getLatestPercentile(percentile);
    }

    /**
     * The mean (average) execution time (in milliseconds) for the {@link HystrixCommand#run()}.
     * <p>
     * This uses the same backing data as {@link #getExecutionTimePercentile};
     * 
     * @return int time in milliseconds
     */
    public int getExecutionTimeMean() {
        return rollingCommandLatencyDistributionStream.getLatestMean();
    }

    /**
     * Retrieve the total end-to-end execution time (in milliseconds) for {@link HystrixCommand#execute()} or {@link HystrixCommand#queue()} at a given percentile.
     * <p>
     * When execution is successful this would include time from {@link #getExecutionTimePercentile} but when execution
     * is being rejected, short-circuited, or timed-out then the time will differ.
     * <p>
     * This time can be lower than {@link #getExecutionTimePercentile} when a timeout occurs and the backing
     * thread that calls {@link HystrixCommand#run()} is still running.
     * <p>
     * When rejections or short-circuits occur then {@link HystrixCommand#run()} will not be executed and thus
     * not contribute time to {@link #getExecutionTimePercentile} but time will still show up in this metric for the end-to-end time.
     * <p>
     * This metric gives visibility into the total cost of {@link HystrixCommand} execution including
     * the overhead of queuing, executing and waiting for a thread to invoke {@link HystrixCommand#run()} .
     * <p>
     * Percentile capture and calculation is configured via {@link HystrixCommandProperties#metricsRollingStatisticalWindowInMilliseconds()} and other related properties.
     * 
     * @param percentile
     *            Percentile such as 50, 99, or 99.5.
     * @return int time in milliseconds
     */
    public int getTotalTimePercentile(double percentile) {
        return rollingCommandUserLatencyDistributionStream.getLatestPercentile(percentile);
    }

    /**
     * The mean (average) execution time (in milliseconds) for {@link HystrixCommand#execute()} or {@link HystrixCommand#queue()}.
     * <p>
     * This uses the same backing data as {@link #getTotalTimePercentile};
     * 
     * @return int time in milliseconds
     */
    public int getTotalTimeMean() {
        return rollingCommandUserLatencyDistributionStream.getLatestMean();
    }

    public long getRollingMaxConcurrentExecutions() {
        return rollingCommandMaxConcurrencyStream.getLatestRollingMax();
    }

    /**
     * Current number of concurrent executions of {@link HystrixCommand#run()};
     * 
     * @return int
     */
    public int getCurrentConcurrentExecutionCount() {
        return concurrentExecutionCount.get();
    }

    /**
     * 传入隔离策略
     * @param commandKey
     * @param threadPoolKey
     * @param isolationStrategy
     */
    /* package-private */ void markCommandStart(HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey, HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy) {
        // 增加当前执行的任务数
        int currentCount = concurrentExecutionCount.incrementAndGet();
        // 使用 eventStream 发射数据
        HystrixThreadEventStream.getInstance().commandExecutionStarted(commandKey, threadPoolKey, isolationStrategy, currentCount);
    }

    /**
     * 标记任务完成
     * @param executionResult 本次执行结果
     * @param commandKey  对应的 command 唯一标识
     * @param threadPoolKey  对象的线程池属性标识
     * @param executionStarted 代表是否正常执行了 代码 用户调用 Command 时 如果出现异常这里是false
     */
    /* package-private */ void markCommandDone(ExecutionResult executionResult, HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey, boolean executionStarted) {
        HystrixThreadEventStream.getInstance().executionDone(executionResult, commandKey, threadPoolKey);
        if (executionStarted) {
            // 减少当前执行的任务数
            concurrentExecutionCount.decrementAndGet();
        }
    }

    /**
     * 返回一个 健康计数流对象
     * @return
     */
    /* package-private */ HealthCountsStream getHealthCountsStream() {
        return healthCountsStream;
    }

    /**
     * Retrieve a snapshot of total requests, error count and error percentage.
     *
     * This metrics should measure the actual health of a {@link HystrixCommand}.  For that reason, the following are included:
     * <p><ul>
     * <li>{@link HystrixEventType#SUCCESS}
     * <li>{@link HystrixEventType#FAILURE}
     * <li>{@link HystrixEventType#TIMEOUT}
     * <li>{@link HystrixEventType#THREAD_POOL_REJECTED}
     * <li>{@link HystrixEventType#SEMAPHORE_REJECTED}
     * </ul><p>
     * The following are not included in either attempts/failures:
     * <p><ul>
     * <li>{@link HystrixEventType#BAD_REQUEST} - this event denotes bad arguments to the command and not a problem with the command
     * <li>{@link HystrixEventType#SHORT_CIRCUITED} - this event measures a health problem in the past, not a problem with the current state
     * <li>{@link HystrixEventType#CANCELLED} - this event denotes a user-cancelled command.  It's not known if it would have been a success or failure, so it shouldn't count for either
     * <li>All Fallback metrics
     * <li>{@link HystrixEventType#EMIT} - this event is not a terminal state for the command
     * <li>{@link HystrixEventType#COLLAPSED} - this event is about the batching process, not the command execution
     * </ul><p>
     * 
     * @return {@link HealthCounts}
     */
    public HealthCounts getHealthCounts() {
        return healthCountsStream.getLatest();
    }

    private void unsubscribeAll() {
        healthCountsStream.unsubscribe();
        rollingCommandEventCounterStream.unsubscribe();
        cumulativeCommandEventCounterStream.unsubscribe();
        rollingCommandLatencyDistributionStream.unsubscribe();
        rollingCommandUserLatencyDistributionStream.unsubscribe();
        rollingCommandMaxConcurrencyStream.unsubscribe();
    }

    /**
     * Number of requests during rolling window.
     * Number that failed (failure + success + timeout + threadPoolRejected + semaphoreRejected).
     * Error percentage;
     * 健康计数器 就是统计异常 百分比 之类的
     */
    public static class HealthCounts {
        /**
         * 总调用次数
         */
        private final long totalCount;
        /**
         * 异常次数
         */
        private final long errorCount;
        /**
         * 异常百分比
         */
        private final int errorPercentage;

        HealthCounts(long total, long error) {
            this.totalCount = total;
            this.errorCount = error;
            if (totalCount > 0) {
                this.errorPercentage = (int) ((double) errorCount / totalCount * 100);
            } else {
                this.errorPercentage = 0;
            }
        }

        /**
         * empty 对象 errorCount 和 totalCount 都是0
         */
        private static final HealthCounts EMPTY = new HealthCounts(0, 0);

        public long getTotalRequests() {
            return totalCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public int getErrorPercentage() {
            return errorPercentage;
        }

        /**
         * 应该是从 bucket对象中 获取事件数据并填充到 count 中
         * @param eventTypeCounts
         * @return
         */
        public HealthCounts plus(long[] eventTypeCounts) {
            long updatedTotalCount = totalCount;
            long updatedErrorCount = errorCount;

            long successCount = eventTypeCounts[HystrixEventType.SUCCESS.ordinal()];
            long failureCount = eventTypeCounts[HystrixEventType.FAILURE.ordinal()];
            long timeoutCount = eventTypeCounts[HystrixEventType.TIMEOUT.ordinal()];
            long threadPoolRejectedCount = eventTypeCounts[HystrixEventType.THREAD_POOL_REJECTED.ordinal()];
            long semaphoreRejectedCount = eventTypeCounts[HystrixEventType.SEMAPHORE_REJECTED.ordinal()];

            // 增加总次数
            updatedTotalCount += (successCount + failureCount + timeoutCount + threadPoolRejectedCount + semaphoreRejectedCount);
            // 增加失败次数
            updatedErrorCount += (failureCount + timeoutCount + threadPoolRejectedCount + semaphoreRejectedCount);
            return new HealthCounts(updatedTotalCount, updatedErrorCount);
        }

        public static HealthCounts empty() {
            return EMPTY;
        }

        public String toString() {
            return "HealthCounts[" + errorCount + " / " + totalCount + " : " + getErrorPercentage() + "%]";
        }
    }
}

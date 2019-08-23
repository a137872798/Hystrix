/**
 * Copyright 2015 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.metric.consumer;

import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.metric.HystrixCommandCompletion;
import com.netflix.hystrix.metric.HystrixCommandCompletionStream;
import rx.functions.Func2;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maintains a stream of rolling health counts for a given Command.
 * There is a rolling window abstraction on this stream.
 * The HealthCounts object is calculated over a window of t1 milliseconds.  This window has b buckets.
 * Therefore, a new HealthCounts object is produced every t2 (=t1/b) milliseconds
 * t1 = {@link HystrixCommandProperties#metricsHealthSnapshotIntervalInMilliseconds()}
 * b = {@link HystrixCommandProperties#metricsRollingStatisticalWindowBuckets()}
 *
 * These values are stable - there's no peeking into a bucket until it is emitted
 *
 * These values get produced and cached in this class.  This value (the latest observed value) may be queried using {@link #getLatest()}.
 * 健康计数 流对象  熔断器会订阅 该对象 便于 判断 是否满足被熔断条件  该对象是 针对 画卷级别的数据统计对象
 */
public class HealthCountsStream extends BucketedRollingCounterStream<HystrixCommandCompletion, long[], HystrixCommandMetrics.HealthCounts> {

    /**
     * 缓存容器  key 应该是 commandKey 的name 属性
     */
    private static final ConcurrentMap<String, HealthCountsStream> streams = new ConcurrentHashMap<String, HealthCountsStream>();

    /**
     * hystrix 所有事件枚举
     */
    private static final int NUM_EVENT_TYPES = HystrixEventType.values().length;

    /**
     * 健康检查累加器  该函数的作用是 每次 将 bucket 中的数据 填充到传入的 healthCounts中
     * 每个 bucket 对象就是一个 long[] 对象 每个下标 的数据对应一种事件
     */
    private static final Func2<HystrixCommandMetrics.HealthCounts, long[], HystrixCommandMetrics.HealthCounts> healthCheckAccumulator = new Func2<HystrixCommandMetrics.HealthCounts, long[], HystrixCommandMetrics.HealthCounts>() {
        @Override
        public HystrixCommandMetrics.HealthCounts call(HystrixCommandMetrics.HealthCounts healthCounts, long[] bucketEventCounts) {
            return healthCounts.plus(bucketEventCounts);
        }
    };


    /**
     * 尝试从缓存中获取对象信息  因为 每个 command 始终对应同一个 HealthCountsStream 所以数据都会 统计到 同一个对象中 这样就可以反映为 针对某个 command 长期调用的数据统计
     * @param commandKey
     * @param properties
     * @return
     */
    public static HealthCountsStream getInstance(HystrixCommandKey commandKey, HystrixCommandProperties properties) {
        // 获取生成健康信息的 快照时间间隔    应该是 要至少 这么久的延时之后才允许返回 健康计数流对象
        // bucket 的 大小 以时间为 单位 也就是 代表着 这个bucket  内的数据是统计了 多少时长内产生的数据
        // 所以 这个 健康数据快照生成间隔 时间 也就对应到 桶的大小 每次 桶内最后累计的数据 正是本次快照的数据
        final int healthCountBucketSizeInMs = properties.metricsHealthSnapshotIntervalInMilliseconds().get();
        // 看来该数值 不允许为 0
        if (healthCountBucketSizeInMs == 0) {
            throw new RuntimeException("You have set the bucket size to 0ms.  Please set a positive number, so that the metric stream can be properly consumed");
        }
        // 统计数据的 窗口大小 (这里 将 millisecond 看作是 容量) / 每个桶的容量 (时间)  得到的就是桶数
        // roll 代表画卷 也就是 总计会 维护多少个 桶的数据 超过的数据就不再维护了  默认一个 roll 中存在 20 个 bucket
        final int numHealthCountBuckets = properties.metricsRollingStatisticalWindowInMilliseconds().get() / healthCountBucketSizeInMs;

        // 获取单例对象
        return getInstance(commandKey, numHealthCountBuckets, healthCountBucketSizeInMs);
    }

    /**
     * 根据 commandKey  桶数  每个桶的 容量 生成 健康数据统计流
     * @param commandKey  command key 对象 同于 标识 对应的 countstream
     * @param numBuckets  桶的数量
     * @param bucketSizeInMs  每个桶统计的时长
     * @return
     */
    public static HealthCountsStream getInstance(HystrixCommandKey commandKey, int numBuckets, int bucketSizeInMs) {
        // 先从缓存中获取
        HealthCountsStream initialStream = streams.get(commandKey.name());
        if (initialStream != null) {
            // 从缓存返回
            return initialStream;
        } else {
            // 尝试生成一个 全新的健康数据流对象
            final HealthCountsStream healthStream;
            synchronized (HealthCountsStream.class) {
                HealthCountsStream existingStream = streams.get(commandKey.name());
                if (existingStream == null) {
                    // 创建一个 新的数据流
                    HealthCountsStream newStream = new HealthCountsStream(commandKey, numBuckets, bucketSizeInMs,
                            // appendEventTobucket 对象 代表从 commandComplete 中结果 取出 并设置到 bucket 中
                            HystrixCommandMetrics.appendEventToBucket);

                    streams.putIfAbsent(commandKey.name(), newStream);
                    healthStream = newStream;
                } else {
                    healthStream = existingStream;
                }
            }
            // 使用 BehaviorSubject 作为 订阅者  该对象 会为 每个 订阅者 返回上个 数据
            healthStream.startCachingStreamValuesIfUnstarted();
            return healthStream;
        }
    }

    /**
     * 清空缓存
     */
    public static void reset() {
        streams.clear();
    }

    /**
     * 从缓存中移除某个数据
     * @param key
     */
    public static void removeByKey(HystrixCommandKey key) {
        streams.remove(key.name());
    }

    /**
     * 初始化一个 healthCountsStream 对象
     * @param commandKey   commandKey 用于保证唯一性
     * @param numBuckets   一共有多少桶
     * @param bucketSizeInMs   每个桶的容量
     * @param reduceCommandCompletion  代表叠加的函数  针对 例如  HystrixCommandCompletion 数据追加到桶中
     */
    private HealthCountsStream(final HystrixCommandKey commandKey, final int numBuckets, final int bucketSizeInMs,
                               Func2<long[], HystrixCommandCompletion, long[]> reduceCommandCompletion) {
        // healthCheckAccumulator 代表将 桶中的数据 追到到 数据流中
        super(HystrixCommandCompletionStream.getInstance(commandKey), numBuckets, bucketSizeInMs, reduceCommandCompletion, healthCheckAccumulator);
    }

    /**
     * 返回一个 空桶
     * @return
     */
    @Override
    long[] getEmptyBucketSummary() {
        return new long[NUM_EVENT_TYPES];
    }

    /**
     * 返回一个HealthCount 中 数值都为 的对象
     * @return
     */
    @Override
    HystrixCommandMetrics.HealthCounts getEmptyOutputValue() {
        return HystrixCommandMetrics.HealthCounts.empty();
    }
}

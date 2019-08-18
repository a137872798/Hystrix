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
 * 健康计数 流对象
 */
public class HealthCountsStream extends BucketedRollingCounterStream<HystrixCommandCompletion, long[], HystrixCommandMetrics.HealthCounts> {

    /**
     * 缓存容器
     */
    private static final ConcurrentMap<String, HealthCountsStream> streams = new ConcurrentHashMap<String, HealthCountsStream>();

    /**
     * hystrix 所有事件枚举
     */
    private static final int NUM_EVENT_TYPES = HystrixEventType.values().length;

    /**
     * 健康检查累加器  该函数的作用是 每次 将 bucket 中的数据 填充到传入的 healthCounts中
     */
    private static final Func2<HystrixCommandMetrics.HealthCounts, long[], HystrixCommandMetrics.HealthCounts> healthCheckAccumulator = new Func2<HystrixCommandMetrics.HealthCounts, long[], HystrixCommandMetrics.HealthCounts>() {
        @Override
        public HystrixCommandMetrics.HealthCounts call(HystrixCommandMetrics.HealthCounts healthCounts, long[] bucketEventCounts) {
            return healthCounts.plus(bucketEventCounts);
        }
    };


    /**
     * 尝试从缓存中获取对象信息
     * @param commandKey
     * @param properties
     * @return
     */
    public static HealthCountsStream getInstance(HystrixCommandKey commandKey, HystrixCommandProperties properties) {
        // 获取生成健康信息的 快照时间间隔
        final int healthCountBucketSizeInMs = properties.metricsHealthSnapshotIntervalInMilliseconds().get();
        if (healthCountBucketSizeInMs == 0) {
            throw new RuntimeException("You have set the bucket size to 0ms.  Please set a positive number, so that the metric stream can be properly consumed");
        }
        // 统计数据的 窗口大小 (这里 将 millisecond 看作是 容量) / 每个桶的容量 (时间)  得到的就是桶数
        final int numHealthCountBuckets = properties.metricsRollingStatisticalWindowInMilliseconds().get() / healthCountBucketSizeInMs;

        return getInstance(commandKey, numHealthCountBuckets, healthCountBucketSizeInMs);
    }

    /**
     * 根据 commandKey  桶数  每个桶的 容量 生成 健康数据统计流
     * @param commandKey
     * @param numBuckets
     * @param bucketSizeInMs
     * @return
     */
    public static HealthCountsStream getInstance(HystrixCommandKey commandKey, int numBuckets, int bucketSizeInMs) {
        // 先从缓存中获取
        HealthCountsStream initialStream = streams.get(commandKey.name());
        if (initialStream != null) {
            // 从缓存返回
            return initialStream;
        } else {
            final HealthCountsStream healthStream;
            synchronized (HealthCountsStream.class) {
                HealthCountsStream existingStream = streams.get(commandKey.name());
                if (existingStream == null) {
                    // 创建一个 新的数据流
                    HealthCountsStream newStream = new HealthCountsStream(commandKey, numBuckets, bucketSizeInMs,
                            // 该对象就是将入参 的 统计值 转移到 bucket中
                            HystrixCommandMetrics.appendEventToBucket);

                    streams.putIfAbsent(commandKey.name(), newStream);
                    healthStream = newStream;
                } else {
                    healthStream = existingStream;
                }
            }
            // 启动
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
     * 返回空值
     * @return
     */
    @Override
    HystrixCommandMetrics.HealthCounts getEmptyOutputValue() {
        return HystrixCommandMetrics.HealthCounts.empty();
    }
}

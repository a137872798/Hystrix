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

import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCollapserMetrics;
import com.netflix.hystrix.HystrixCollapserProperties;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.metric.HystrixCollapserEvent;
import com.netflix.hystrix.metric.HystrixCollapserEventStream;
import rx.functions.Func2;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maintains a stream of event counters for a given Command.
 * There is no rolling window abstraction on this stream - every event since the start of the JVM is kept track of.
 * The event counters object is calculated on the same schedule as the rolling abstract {@link RollingCommandEventCounterStream},
 * so bucket rolls correspond to new data in this stream, though data never goes out of window in this stream.
 *
 * Therefore, a new set of counters is produced every t2 (=t1/b) milliseconds
 * t1 = {@link com.netflix.hystrix.HystrixCollapserProperties#metricsRollingStatisticalWindowInMilliseconds()}
 * b = {@link com.netflix.hystrix.HystrixCollapserProperties#metricsRollingStatisticalWindowBuckets()}
 *
 * These values are stable - there's no peeking into a bucket until it is emitted
 *
 * These values get produced and cached in this class.  This value (the latest observed value) may be queried using {@link #getLatest(HystrixEventType.Collapser)}.
 * 针对碰撞事件的 数据流统计  子类定义了 泛型的实现
 */
public class CumulativeCollapserEventCounterStream extends BucketedCumulativeCounterStream<HystrixCollapserEvent, long[], long[]> {

    /**
     * 缓存流对象
     */
    private static final ConcurrentMap<String, CumulativeCollapserEventCounterStream> streams = new ConcurrentHashMap<String, CumulativeCollapserEventCounterStream>();

    /**
     * 所有的事件类型
     */
    private static final int NUM_EVENT_TYPES = HystrixEventType.Collapser.values().length;

    /**
     * 这里根据不同的指标 生成不同的 bucket 对象 (即计算大小的方式不同)
     * @param collapserKey
     * @param properties
     * @return
     */
    public static CumulativeCollapserEventCounterStream getInstance(HystrixCollapserKey collapserKey, HystrixCollapserProperties properties) {
        // 获取统计窗口大小
        final int counterMetricWindow = properties.metricsRollingStatisticalWindowInMilliseconds().get();
        // 统计 的bucket 数量
        final int numCounterBuckets = properties.metricsRollingStatisticalWindowBuckets().get();
        // 比值就是 每个bucket 的容量大小
        final int counterBucketSizeInMs = counterMetricWindow / numCounterBuckets;

        return getInstance(collapserKey, numCounterBuckets, counterBucketSizeInMs);
    }

    /**
     * 获取事件对象实例
     * @param collapserKey
     * @param numBuckets
     * @param bucketSizeInMs
     * @return
     */
    public static CumulativeCollapserEventCounterStream getInstance(HystrixCollapserKey collapserKey, int numBuckets, int bucketSizeInMs) {
        CumulativeCollapserEventCounterStream initialStream = streams.get(collapserKey.name());
        if (initialStream != null) {
            return initialStream;
        } else {
            synchronized (CumulativeCollapserEventCounterStream.class) {
                CumulativeCollapserEventCounterStream existingStream = streams.get(collapserKey.name());
                if (existingStream == null) {
                    // 初始化对象 核心就是这里传入的 函数对象
                    CumulativeCollapserEventCounterStream newStream = new CumulativeCollapserEventCounterStream(collapserKey, numBuckets, bucketSizeInMs, HystrixCollapserMetrics.appendEventToBucket, HystrixCollapserMetrics.bucketAggregator);
                    streams.putIfAbsent(collapserKey.name(), newStream);
                    return newStream;
                } else {
                    return existingStream;
                }
            }
        }
    }

    public static void reset() {
        streams.clear();
    }

    private CumulativeCollapserEventCounterStream(HystrixCollapserKey collapserKey, int numCounterBuckets, int counterBucketSizeInMs,
                                                Func2<long[], HystrixCollapserEvent, long[]> appendEventToBucket,
                                                Func2<long[], long[], long[]> reduceBucket) {
        super(HystrixCollapserEventStream.getInstance(collapserKey), numCounterBuckets, counterBucketSizeInMs, appendEventToBucket, reduceBucket);
    }

    /**
     * 返回空容器
     * @return
     */
    @Override
    long[] getEmptyBucketSummary() {
        return new long[NUM_EVENT_TYPES];
    }

    /**
     * 返回空容器
     * @return
     */
    @Override
    long[] getEmptyOutputValue() {
        return new long[NUM_EVENT_TYPES];
    }

    /**
     * 根据事件类型 返回对应的下标
     * @param eventType
     * @return
     */
    public long getLatest(HystrixEventType.Collapser eventType) {
        return getLatest()[eventType.ordinal()];
    }
}

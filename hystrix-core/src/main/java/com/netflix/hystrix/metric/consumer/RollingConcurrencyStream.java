/**
 * Copyright 2016 Netflix, Inc.
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

import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.metric.HystrixCommandExecutionStarted;
import com.netflix.hystrix.metric.HystrixEventStream;
import rx.Observable;
import rx.Subscription;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.subjects.BehaviorSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains a stream of max-concurrency
 *
 * This gets calculated using a rolling window of t1 milliseconds.  This window has b buckets.
 * Therefore, a new rolling-max is produced every t2 (=t1/b) milliseconds
 * t1 = {@link HystrixCommandProperties#metricsRollingStatisticalWindowInMilliseconds()}
 * b = {@link HystrixCommandProperties#metricsRollingStatisticalWindowBuckets()}
 *
 * This value gets cached in this class.  It may be queried using {@link #getLatestRollingMax()}
 *
 * This is a stable value - there's no peeking into a bucket until it is emitted
 * 并发数 数据流
 */
public abstract class RollingConcurrencyStream {
    /**
     * 订阅者对象
     */
    private AtomicReference<Subscription> rollingMaxSubscription = new AtomicReference<Subscription>(null);
    /**
     * 滚动最大值  0 代表的是默认值
     */
    private final BehaviorSubject<Integer> rollingMax = BehaviorSubject.create(0);
    /**
     * 滚动最大值
     */
    private final Observable<Integer> rollingMaxStream;

    /**
     * 返回最大值
     */
    private static final Func2<Integer, Integer, Integer> reduceToMax = new Func2<Integer, Integer, Integer>() {
        @Override
        public Integer call(Integer a, Integer b) {
            return Math.max(a, b);
        }
    };

    /**
     * reduce 代表将 每2个 元素 按传入的函数处理后将结果   最后只触发一次 发射
     */
    private static final Func1<Observable<Integer>, Observable<Integer>> reduceStreamToMax = new Func1<Observable<Integer>, Observable<Integer>>() {
        @Override
        public Observable<Integer> call(Observable<Integer> observedConcurrency) {
            return observedConcurrency.reduce(0, reduceToMax);
        }
    };

    /**
     * 该函数 会从 started 对象中  获取并发调用次数
     */
    private static final Func1<HystrixCommandExecutionStarted, Integer> getConcurrencyCountFromEvent = new Func1<HystrixCommandExecutionStarted, Integer>() {
        @Override
        public Integer call(HystrixCommandExecutionStarted event) {
            return event.getCurrentConcurrency();
        }
    };

    /**
     * 并发数据流对象
     * @param inputEventStream
     * @param numBuckets
     * @param bucketSizeInMs
     */
    protected RollingConcurrencyStream(final HystrixEventStream<HystrixCommandExecutionStarted> inputEventStream, final int numBuckets, final int bucketSizeInMs) {
        final List<Integer> emptyRollingMaxBuckets = new ArrayList<Integer>();
        // 按照bucket 数量 来初始化 List 对象
        for (int i = 0; i < numBuckets; i++) {
            emptyRollingMaxBuckets.add(0);
        }

        rollingMaxStream = inputEventStream
                // 从对象流中 获取 可观察对象
                .observe()
                // 从观察对象中 获取 当前并发数
                .map(getConcurrencyCountFromEvent)
                // 按照bucket 对应的 时间容量 返回不同时间的 Observable 对象
                .window(bucketSizeInMs, TimeUnit.MILLISECONDS)
                // 返回 最大值  也就是 按照每个 bucket的时间来将 当中并发数最大的那个
                .flatMap(reduceStreamToMax)
                // 在 头部插入指定的数据
                .startWith(emptyRollingMaxBuckets)
                // 每经过一个 元素 就创建一个新的 Observable 对象 每个对象 包含 numBuckets 个 桶对象
                .window(numBuckets, 1)
                // 返回最大值
                .flatMap(reduceStreamToMax)
                // 开启广播
                .share()
                // 开启背压
                .onBackpressureDrop();
    }

    /**
     * 生成订阅者
     */
    public void startCachingStreamValuesIfUnstarted() {
        if (rollingMaxSubscription.get() == null) {
            //the stream is not yet started
            // 将 subject 当作订阅者对象
            Subscription candidateSubscription = observe().subscribe(rollingMax);
            if (rollingMaxSubscription.compareAndSet(null, candidateSubscription)) {
                //won the race to set the subscription
            } else {
                //lost the race to set the subscription, so we need to cancel this one
                candidateSubscription.unsubscribe();
            }
        }
    }

    public long getLatestRollingMax() {
        // 首先根据需要生成 订阅者对象
        startCachingStreamValuesIfUnstarted();
        if (rollingMax.hasValue()) {
            // 当前订阅者包含数据的情况下 返回数据
            return rollingMax.getValue();
        } else {
            return 0L;
        }
    }

    /**
     * 返回处理过的 观察者对象
     * @return
     */
    public Observable<Integer> observe() {
        return rollingMaxStream;
    }

    public void unsubscribe() {
        Subscription s = rollingMaxSubscription.get();
        if (s != null) {
            s.unsubscribe();
            rollingMaxSubscription.compareAndSet(s, null);
        }
    }
}

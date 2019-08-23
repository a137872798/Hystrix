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

import com.netflix.hystrix.metric.HystrixEvent;
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
 * Abstract class that imposes a bucketing structure and provides streams of buckets
 *
 * @param <Event> type of raw data that needs to get summarized into a bucket
 * @param <Bucket> type of data contained in each bucket
 * @param <Output> type of data emitted to stream subscribers (often is the same as A but does not have to be)
 *                以单个桶为单位的 计数流对象
 */
public abstract class BucketedCounterStream<Event extends HystrixEvent, Bucket, Output> {
    /**
     * 桶数量
     */
    protected final int numBuckets;
    /**
     * 可观察对象 对应Rxjava 中的观察者 在添加 订阅者时 会触发一系列的函数
     * 该对象通过初始化传入的 inputEventStream 转化过来
     */
    protected final Observable<Bucket> bucketedStream;
    /**
     * 订阅者对象  可以 通过 Observable.subscripe(Subscription) 订阅数据
     */
    protected final AtomicReference<Subscription> subscription = new AtomicReference<Subscription>(null);

    /**
     * 将 obserable 对象 转换成bucket 对象
     */
    private final Func1<Observable<Event>, Observable<Bucket>> reduceBucketToSummary;

    /**
     * Subject 是一个 即是 Observable 也是 Subscription 的接口
     * 该类的特性是 会将 上个观察到的最新项 以及之后的 数据发送给 订阅端
     * .create 代表初始化这个 可订阅对象
     */
    private final BehaviorSubject<Output> counterSubject = BehaviorSubject.create(getEmptyOutputValue());

    /**
     * 桶计数流
     * @param inputEventStream  内部包含一个 observe() 方法 返回一个可观察对象
     * @param numBuckets     限定的 buckets 数量
     * @param bucketSizeInMs
     * @param appendRawEventToBucket 追加原始事件到 bucket
     */
    protected BucketedCounterStream(final HystrixEventStream<Event> inputEventStream, final int numBuckets, final int bucketSizeInMs,
                                    final Func2<Bucket, Event, Bucket> appendRawEventToBucket) {
        this.numBuckets = numBuckets;

        // 初始化 减少桶的 函数  param1 代表入参类型 param2 代表 返回类型
        // 该函数应该是代表 将 发射下来的所有数据 按照 appendRawEventToBucket 汇聚成一个数据  eventBucket就是上游的初始事件对象 比如 HealthCountStream
        this.reduceBucketToSummary = new Func1<Observable<Event>, Observable<Bucket>>() {
            @Override
            public Observable<Bucket> call(Observable<Event> eventBucket) {
                // 第一个参数 代表 初始值 第二个参数代表 叠加逻辑
                return eventBucket.reduce(getEmptyBucketSummary(), appendRawEventToBucket);
            }
        };

        // 生成一个 固定的 observable 数据头 代表每次发射 都会携带这些数据
        final List<Bucket> emptyEventCountsToStart = new ArrayList<Bucket>();
        for (int i = 0; i < numBuckets; i++) {
            // 为每个 桶对象 添加 初始空数据 每个桶 中记录了 所有事件的值 一开始所有事件数据都是 空  多个 空桶就可以看作是 画卷 roll 的 初始状态  | 0| 0| 0| 0|  类似这样
            emptyEventCountsToStart.add(getEmptyBucketSummary());
        }

        // defer 方法会给 每个 订阅者返回一个 新的 Observable 对象 对象内部 包含了一个 临时的 Observable  临时的对象负责调用下面的 call 方法
        this.bucketedStream = Observable.defer(new Func0<Observable<Bucket>>() {

            // 当被订阅后会 触发这个方法  并返回了一个新的 observable 对象 并且会调用这个对象的 unsubscribe
            @Override
            public Observable<Bucket> call() {
                // 获取上游对象
                return inputEventStream
                        // 获取上游的可读数据源  就是 直接接收 hystrix 下发的事件
                        .observe()
                        // 每次 按照一个桶的 时间 返回一个元素  原本 每次 触发一次 CommandComplete 事件 就会发射一个元素  这样就限制成 按照 bucket 为单位来发射数据(以observable 的形式)
                        .window(bucketSizeInMs, TimeUnit.MILLISECONDS) //bucket it by the counter window so we can emit to the next operator in time chunks, not on every OnNext
                        // window 会 下发多个数据流 然后 reduceBucketToSummary 内部使用 reduce 将数据流 也就是一个桶的时间内的数据(发射多个对象) 汇聚成 一个数据源 (发射单个对象)
                        .flatMap(reduceBucketToSummary)                //for a given bucket, turn it into a long array containing counts of event types
                        // 为每个 结果 添加一个 固定的数据头 (也就是前面的 空 画卷)  保证子类调用 window 时
                        .startWith(emptyEventCountsToStart);           //start it with empty arrays to make consumer logic as generic as possible (windows are always full)
            }
        });
    }

    /**
     * 空 bucket 概要???
     * @return
     */
    abstract Bucket getEmptyBucketSummary();

    abstract Output getEmptyOutputValue();

    /**
     * Return the stream of buckets
     * @return stream of buckets
     * 应该是 返回 bucketStream
     */
    public abstract Observable<Output> observe();

    /**
     * 当 订阅者还没有创建时 使用subject订阅可观察对象  一般情况下在 创建子类的 数据流时 就会调用该方法 为 observable 对象设置订阅者
     */
    public void startCachingStreamValuesIfUnstarted() {
        if (subscription.get() == null) {
            //the stream is not yet started
            // 使用一个 subject 作为订阅者
            Subscription candidateSubscription = observe().subscribe(counterSubject);
            if (subscription.compareAndSet(null, candidateSubscription)) {
                //won the race to set the subscription
            } else {
                //lost the race to set the subscription, so we need to cancel this one
                candidateSubscription.unsubscribe();
            }
        }
    }

    /**
     * Synchronous call to retrieve the last calculated bucket without waiting for any emissions
     * @return last calculated bucket
     * 获取进行订阅后 返回的数据
     */
    public Output getLatest() {
        startCachingStreamValuesIfUnstarted();
        if (counterSubject.hasValue()) {
            return counterSubject.getValue();
        } else {
            return getEmptyOutputValue();
        }
    }

    public void unsubscribe() {
        Subscription s = subscription.get();
        if (s != null) {
            s.unsubscribe();
            subscription.compareAndSet(s, null);
        }
    }
}

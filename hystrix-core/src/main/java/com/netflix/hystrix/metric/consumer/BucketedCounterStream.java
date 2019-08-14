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
 *                桶计数流 泛型相关的 3个参数 event 代表 hystrixEvent 第二个参数 代表 long[] 代表 bucket 中的数据  output 应该是输出的目标
 */
public abstract class BucketedCounterStream<Event extends HystrixEvent, Bucket, Output> {
    /**
     * 桶数量
     */
    protected final int numBuckets;
    /**
     * 可观察对象 对应Rxjava 中的观察者 在添加 订阅者时 会触发一系列的函数
     */
    protected final Observable<Bucket> bucketedStream;
    /**
     * 订阅者对象  可以 通过 Observable.subscripe(Subscription) 订阅数据
     */
    protected final AtomicReference<Subscription> subscription = new AtomicReference<Subscription>(null);

    /**
     * 减少桶中的数据 ???
     */
    private final Func1<Observable<Event>, Observable<Bucket>> reduceBucketToSummary;

    /**
     * Subject 是一个 即是 Observable 也是 Subscription 的接口
     * 该类的特性是 会将 当前观察到的最新项 以及之后的 数据发送给 订阅端
     */
    private final BehaviorSubject<Output> counterSubject = BehaviorSubject.create(getEmptyOutputValue());

    /**
     * 桶计数流
     * @param inputEventStream  内部包含一个 observe() 方法 返回一个可观察对象
     * @param numBuckets     限定的 buckets 数量
     * @param bucketSizeInMs
     * @param appendRawEventToBucket
     */
    protected BucketedCounterStream(final HystrixEventStream<Event> inputEventStream, final int numBuckets, final int bucketSizeInMs,
                                    final Func2<Bucket, Event, Bucket> appendRawEventToBucket) {
        this.numBuckets = numBuckets;
        this.reduceBucketToSummary = new Func1<Observable<Event>, Observable<Bucket>>() {
            @Override
            public Observable<Bucket> call(Observable<Event> eventBucket) {
                return eventBucket.reduce(getEmptyBucketSummary(), appendRawEventToBucket);
            }
        };

        final List<Bucket> emptyEventCountsToStart = new ArrayList<Bucket>();
        for (int i = 0; i < numBuckets; i++) {
            emptyEventCountsToStart.add(getEmptyBucketSummary());
        }

        this.bucketedStream = Observable.defer(new Func0<Observable<Bucket>>() {
            @Override
            public Observable<Bucket> call() {
                return inputEventStream
                        .observe()
                        .window(bucketSizeInMs, TimeUnit.MILLISECONDS) //bucket it by the counter window so we can emit to the next operator in time chunks, not on every OnNext
                        .flatMap(reduceBucketToSummary)                //for a given bucket, turn it into a long array containing counts of event types
                        .startWith(emptyEventCountsToStart);           //start it with empty arrays to make consumer logic as generic as possible (windows are always full)
            }
        });
    }

    abstract Bucket getEmptyBucketSummary();

    abstract Output getEmptyOutputValue();

    /**
     * Return the stream of buckets
     * @return stream of buckets
     */
    public abstract Observable<Output> observe();

    public void startCachingStreamValuesIfUnstarted() {
        if (subscription.get() == null) {
            //the stream is not yet started
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

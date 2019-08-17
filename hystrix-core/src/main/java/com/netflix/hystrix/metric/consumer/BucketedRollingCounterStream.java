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
import rx.functions.Action0;
import rx.functions.Func1;
import rx.functions.Func2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refinement of {@link BucketedCounterStream} which reduces numBuckets at a time.
 *
 * @param <Event> type of raw data that needs to get summarized into a bucket
 * @param <Bucket> type of data contained in each bucket
 * @param <Output> type of data emitted to stream subscribers (often is the same as A but does not have to be)
 *                滚动数据流 同级的是  BucketedCumulativeCounterStream
 */
public abstract class BucketedRollingCounterStream<Event extends HystrixEvent, Bucket, Output> extends BucketedCounterStream<Event, Bucket, Output> {
    /**
     * 被加工处理过的 数据流
     */
    private Observable<Output> sourceStream;
    /**
     * 当前是否被订阅
     */
    private final AtomicBoolean isSourceCurrentlySubscribed = new AtomicBoolean(false);

    /**
     *
     * @param stream 传入的事件流
     * @param numBuckets 桶数
     * @param bucketSizeInMs 每过多少秒 切换到下一个桶
     * @param appendRawEventToBucket  将数据追加到 桶中
     * @param reduceBucket   好像是从桶中 取出数据到 输出流对象（非java.io 对象）
     */
    protected BucketedRollingCounterStream(HystrixEventStream<Event> stream, final int numBuckets, int bucketSizeInMs,
                                           final Func2<Bucket, Event, Bucket> appendRawEventToBucket,
                                           final Func2<Output, Bucket, Output> reduceBucket) {
        super(stream, numBuckets, bucketSizeInMs, appendRawEventToBucket);
        Func1<Observable<Bucket>, Observable<Output>> reduceWindowToSummary = new Func1<Observable<Bucket>, Observable<Output>>() {
            @Override
            public Observable<Output> call(Observable<Bucket> window) {
                // 每2个数据 使用一个函数处理并 发射   deduce 是将所有数据处理完后再一次性发射
                return window.scan(getEmptyOutputValue(), reduceBucket).skip(numBuckets);
            }
        };
        this.sourceStream = bucketedStream      //stream broken up into buckets
                // 每发射 1 个对象 就 开启一个新的 window 当发射 numbucket 的元素后 关闭当前window
                .window(numBuckets, 1)          //emit overlapping windows of buckets
                // 将 一个数据流 转换成多个 数据流
                .flatMap(reduceWindowToSummary) //convert a window of bucket-summaries into a single summary
                .doOnSubscribe(new Action0() {
                    @Override
                    public void call() {
                        isSourceCurrentlySubscribed.set(true);
                    }
                })
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        isSourceCurrentlySubscribed.set(false);
                    }
                })
                // 广播
                .share()                        //multiple subscribers should get same data
                // 开启背压
                .onBackpressureDrop();          //if there are slow consumers, data should not buffer
    }

    @Override
    public Observable<Output> observe() {
        return sourceStream;
    }

    /* package-private */ boolean isSourceCurrentlySubscribed() {
        return isSourceCurrentlySubscribed.get();
    }
}

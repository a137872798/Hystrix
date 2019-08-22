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
 *                该对象是以 画卷为单位的数据流 就是一个数据流中能 返回 当前画卷状态所有的 bucket
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
     * @param stream 代表上游的数据源
     * @param numBuckets 桶数
     * @param bucketSizeInMs 每个桶 记录的 数据时间
     * @param appendRawEventToBucket  代表 根据 hystrix 事件 将数据转换到桶中的函数
     * @param reduceBucket  聚合桶中数据的函数  reduce 在 rxjava 代表 将多个 发射出的数据 聚合成一个数据
     */
    protected BucketedRollingCounterStream(HystrixEventStream<Event> stream, final int numBuckets, int bucketSizeInMs,
                                           final Func2<Bucket, Event, Bucket> appendRawEventToBucket,
                                           final Func2<Output, Bucket, Output> reduceBucket) {
        // 上层初始化了一个 接收event数据流 并按照 bucket 的时间将数据 汇聚成单个 数据源 (发射单个对象) 并会追加 固定数量的 空 bucket
        super(stream, numBuckets, bucketSizeInMs, appendRawEventToBucket);
        Func1<Observable<Bucket>, Observable<Output>> reduceWindowToSummary = new Func1<Observable<Bucket>, Observable<Output>>() {
            @Override
            public Observable<Output> call(Observable<Bucket> window) {
                // 每2个数据 使用一个函数处理并 发射   deduce 是将所有数据处理完后再一次性发射  这里跳过 numbucket 应该就是对应父类会 添加 numbucket 的 空对象
                return window.scan(getEmptyOutputValue(), reduceBucket).skip(numBuckets);
            }
        };
        // bucketedStream 就是将 eventStream 桶化的结果
        this.sourceStream = bucketedStream      //stream broken up into buckets
                // 每经过 一个 单元的数据 （父类 按照 bucket 作为一个单位）打开一个新的 数据源 每个数据源 包含 画卷大小的bucket 也就是 该对象 是以 画卷为单位 父类以 bucket 为单位
                .window(numBuckets, 1)          //emit overlapping windows of buckets
                // 代表每个 传入的 数据源 执行reduceWindowToSummary  也就是以画卷为单位  scan 代表 每发射 的一个新元素就是  之前全局元素值的总和  1,2,3,4 -> 1,3,6,10
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
                // 保证 之后所有订阅者共享同一份数据
                .share()                        //multiple subscribers should get same data
                // 使用丢弃的背压策略
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

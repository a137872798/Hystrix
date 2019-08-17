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
import rx.functions.Func2;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refinement of {@link BucketedCounterStream} which accumulates counters infinitely in the bucket-reduction step
 *
 * @param <Event> type of raw data that needs to get summarized into a bucket
 * @param <Bucket> type of data contained in each bucket
 * @param <Output> type of data emitted to stream subscribers (often is the same as A but does not have to be)
 *                积累的 桶计数流
 */
public abstract class BucketedCumulativeCounterStream<Event extends HystrixEvent, Bucket, Output> extends BucketedCounterStream<Event, Bucket, Output> {

    /**
     * 用于初始化 生成 bucketStream 的源数据流
     */
    private Observable<Output> sourceStream;
    /**
     * 当前数据流是否被订阅
     */
    private final AtomicBoolean isSourceCurrentlySubscribed = new AtomicBoolean(false);

    /**
     * 通过一些 函数对象来初始化 数据流
     * @param stream  事件源数据流
     * @param numBuckets  bucket 数量
     * @param bucketSizeInMs   每多少时间更换一个桶
     * @param reduceCommandCompletion   reduce 命令结束后执行的函数  reduce 就是将 流中全部数据处理完后触发一次 onNext
     * @param reduceBucket     reduce 桶 ???
     */
    protected BucketedCumulativeCounterStream(HystrixEventStream<Event> stream, int numBuckets, int bucketSizeInMs,
                                              Func2<Bucket, Event, Bucket> reduceCommandCompletion,
                                              Func2<Output, Bucket, Output> reduceBucket) {
        super(stream, numBuckets, bucketSizeInMs, reduceCommandCompletion);

        // bucket source 是 通过 的defer 生成的 observable 对象
        this.sourceStream = bucketedStream
                // 每连续的 2个对象 使用一个函数处理后返回  getEmptyOutputValue() 代表初始值 reduceBucket 代表累加的逻辑
                .scan(getEmptyOutputValue(), reduceBucket)
                // 跳过前几个元素
                .skip(numBuckets)
                // 当被订阅时触发
                .doOnSubscribe(new Action0() {
                    @Override
                    public void call() {
                        isSourceCurrentlySubscribed.set(true);
                    }
                })
                // 接触订阅时触发
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        isSourceCurrentlySubscribed.set(false);
                    }
                })
                // 代表多个 subscribe 会收到相同的数据
                .share()                        //multiple subscribers should get same data
                // 如果消耗速度缓慢 丢弃数据 也就是背压
                .onBackpressureDrop();          //if there are slow consumers, data should not buffer
    }

    /**
     * 将加工后的 sourceStream 返回
     * @return
     */
    @Override
    public Observable<Output> observe() {
        return sourceStream;
    }
}

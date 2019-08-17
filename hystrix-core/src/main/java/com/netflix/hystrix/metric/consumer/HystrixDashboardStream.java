/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.metric.consumer;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.hystrix.HystrixCollapserMetrics;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仪器板 数据流 ???
 */
public class HystrixDashboardStream {
    /**
     * 延迟的时间
     */
    final int delayInMs;
    /**
     * 可观察的数据对象
     */
    final Observable<DashboardData> singleSource;
    /**
     * 当前对象是否被订阅
     */
    final AtomicBoolean isSourceCurrentlySubscribed = new AtomicBoolean(false);

    /**
     * 更新仪器版数据的时间间隔 也就是发射数据的时间间隔
     */
    private static final DynamicIntProperty dataEmissionIntervalInMs =
            DynamicPropertyFactory.getInstance().getIntProperty("hystrix.stream.dashboard.intervalInMilliseconds", 500);

    /**
     * 通过一个 延迟时间来初始化  仪器版数据流对象
     * @param delayInMs
     */
    private HystrixDashboardStream(int delayInMs) {
        this.delayInMs = delayInMs;
        // interval 代表每隔一段时间 发送一个对象  interval 只能返回 Long 类型
        this.singleSource = Observable.interval(delayInMs, TimeUnit.MILLISECONDS)
                .map(new Func1<Long, DashboardData>() {
                    @Override
                    public DashboardData call(Long timestamp) {
                        return new DashboardData(
                                // getInstance 返回的是统计数据的视图对象
                                HystrixCommandMetrics.getInstances(),
                                HystrixThreadPoolMetrics.getInstances(),
                                HystrixCollapserMetrics.getInstances()
                        );
                    }
                })
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
                // 广播数据流
                .share()
                // 开启背压
                .onBackpressureDrop();
    }

    //The data emission interval is looked up on startup only
    private static final HystrixDashboardStream INSTANCE =
            new HystrixDashboardStream(dataEmissionIntervalInMs.get());

    public static HystrixDashboardStream getInstance() {
        return INSTANCE;
    }

    static HystrixDashboardStream getNonSingletonInstanceOnlyUsedInUnitTests(int delayInMs) {
        return new HystrixDashboardStream(delayInMs);
    }

    /**
     * Return a ref-counted stream that will only do work when at least one subscriber is present
     * 返回的可观察对象是 数据统计版
     */
    public Observable<DashboardData> observe() {
        return singleSource;
    }

    public boolean isSourceCurrentlySubscribed() {
        return isSourceCurrentlySubscribed.get();
    }

    /**
     * 控制板上要显示的数据
     */
    public static class DashboardData {

        // 首先要搞清楚  在 hystrix 中存在3种 统计对象 一种是针对 command 的 一种是 针对 threadpool 一种是 collapser 每种需要统计的数据都有自己的 CounterStream 对象
        // 并且控制板对象中 内置了这3种数据流
        final Collection<HystrixCommandMetrics> commandMetrics;
        final Collection<HystrixThreadPoolMetrics> threadPoolMetrics;
        final Collection<HystrixCollapserMetrics> collapserMetrics;

        public DashboardData(Collection<HystrixCommandMetrics> commandMetrics, Collection<HystrixThreadPoolMetrics> threadPoolMetrics, Collection<HystrixCollapserMetrics> collapserMetrics) {
            this.commandMetrics = commandMetrics;
            this.threadPoolMetrics = threadPoolMetrics;
            this.collapserMetrics = collapserMetrics;
        }

        public Collection<HystrixCommandMetrics> getCommandMetrics() {
            return commandMetrics;
        }

        public Collection<HystrixThreadPoolMetrics> getThreadPoolMetrics() {
            return threadPoolMetrics;
        }

        public Collection<HystrixCollapserMetrics> getCollapserMetrics() {
            return collapserMetrics;
        }
    }
}



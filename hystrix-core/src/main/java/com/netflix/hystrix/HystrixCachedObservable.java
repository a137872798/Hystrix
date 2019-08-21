package com.netflix.hystrix;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.subjects.ReplaySubject;

/**
 * 代表 hystrix 的 可缓存结果对象
 *
 * @param <R>
 */
public class HystrixCachedObservable<R> {
    /**
     * 订阅者对象
     */
    protected final Subscription originalSubscription;
    /**
     * 可观察对象
     */
    protected final Observable<R> cachedObservable;
    /**
     * 该数据相当于是 一个 计数器  记录当前一共有多少订阅者 之后订阅者全部取消后才会真正执行 unsubscribe 方法
     */
    private volatile int outstandingSubscriptions = 0;

    protected HystrixCachedObservable(final Observable<R> originalObservable) {
        // 该对象会 发送 可观察对象 一开始的 全部数据  为什么用subject 就是为了 做代理 让数据 订阅它
        ReplaySubject<R> replaySubject = ReplaySubject.create();
        // 该对象为 可观察对象被订阅后生成的
        this.originalSubscription = originalObservable
                .subscribe(replaySubject);

        // 可观察对象
        this.cachedObservable = replaySubject
                // 取消订阅
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        outstandingSubscriptions--;
                        if (outstandingSubscriptions == 0) {
                            originalSubscription.unsubscribe();
                        }
                    }
                })
                // 订阅时 增加计数器
                .doOnSubscribe(new Action0() {
                    @Override
                    public void call() {
                        outstandingSubscriptions++;
                    }
                });
    }

    /**
     * 返回一个 针对 command的 res缓存对象
     * @param o
     * @param originalCommand
     * @param <R>
     * @return
     */
    public static <R> HystrixCachedObservable<R> from(Observable<R> o, AbstractCommand<R> originalCommand) {
        return new HystrixCommandResponseFromCache<R>(o, originalCommand);
    }

    /**
     * 返回普通的 缓存对象
     * @param o
     * @param <R>
     * @return
     */
    public static <R> HystrixCachedObservable<R> from(Observable<R> o) {
        return new HystrixCachedObservable<R>(o);
    }

    /**
     * 返回一个 经过代理后的数据流
     * @return
     */
    public Observable<R> toObservable() {
        return cachedObservable;
    }

    public void unsubscribe() {
        originalSubscription.unsubscribe();
    }
}

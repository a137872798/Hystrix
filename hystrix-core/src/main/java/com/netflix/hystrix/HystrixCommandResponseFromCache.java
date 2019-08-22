package com.netflix.hystrix;

import rx.Observable;
import rx.functions.Action0;
import rx.functions.Action1;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * hystrix 的 缓存结果对象
 * @param <R>
 */
public class HystrixCommandResponseFromCache<R> extends HystrixCachedObservable<R> {
    /**
     * 内部 维护一个 command 对象
     */
    private final AbstractCommand<R> originalCommand;

    /* package-private */ HystrixCommandResponseFromCache(Observable<R> originalObservable, final AbstractCommand<R> originalCommand) {
        super(originalObservable);
        this.originalCommand = originalCommand;
    }

    /**
     * 将结果数据 copy 到 传入的 command 中
     * @param commandToCopyStateInto
     * @return
     */
    public Observable<R> toObservableWithStateCopiedInto(final AbstractCommand<R> commandToCopyStateInto) {
        // 代表还没有处理完
        final AtomicBoolean completionLogicRun = new AtomicBoolean(false);

        // 返回一个新的 可观察对象
        return cachedObservable
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        // 该对象在接收到异常时 将 originalCommand 的 结果设置到入参
                        if (completionLogicRun.compareAndSet(false, true)) {
                            commandCompleted(commandToCopyStateInto);
                        }
                    }
                })
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        // 将 originalCommand 的结果设置到入参
                        if (completionLogicRun.compareAndSet(false, true)) {
                            commandCompleted(commandToCopyStateInto);
                        }
                    }
                })
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        if (completionLogicRun.compareAndSet(false, true)) {
                            commandUnsubscribed(commandToCopyStateInto);
                        }
                    }
                });
    }

    private void commandCompleted(final AbstractCommand<R> commandToCopyStateInto) {
        commandToCopyStateInto.executionResult = originalCommand.executionResult;
    }

    private void commandUnsubscribed(final AbstractCommand<R> commandToCopyStateInto) {
        commandToCopyStateInto.executionResult = commandToCopyStateInto.executionResult.addEvent(HystrixEventType.CANCELLED);
        commandToCopyStateInto.executionResult = commandToCopyStateInto.executionResult.setExecutionLatency(-1);
    }
}

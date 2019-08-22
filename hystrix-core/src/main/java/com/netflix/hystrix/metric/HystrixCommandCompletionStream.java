/**
 * Copyright 2015 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.metric;

import com.netflix.hystrix.HystrixCommandKey;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-Command stream of {@link HystrixCommandCompletion}s.  This gets written to by {@link HystrixThreadEventStream}s.
 * Events are emitted synchronously in the same thread that performs the command execution.
 * 该对象 代表命令完成时的 数据流对象 HystrixEventStream 只是一个 返回 observable 的接口  代表该数据流会返回 commandComplete 对象
 * 每个 commandComplete 对象 绑定在 一个 command 上
 */
public class HystrixCommandCompletionStream implements HystrixEventStream<HystrixCommandCompletion> {
    /**
     * 标识本次的command 对象
     */
    private final HystrixCommandKey commandKey;

    /**
     * 代表 该 对象只用于写入
     */
    private final Subject<HystrixCommandCompletion, HystrixCommandCompletion> writeOnlySubject;
    /**
     * 代表 该对象 用于 订阅
     */
    private final Observable<HystrixCommandCompletion> readOnlyStream;

    /**
     * 缓存对象 key 是  commandKey
     */
    private static final ConcurrentMap<String, HystrixCommandCompletionStream> streams = new ConcurrentHashMap<String, HystrixCommandCompletionStream>();

    /**
     * 通过 commandKey 来初始化流对象
     * @param commandKey
     * @return
     */
    public static HystrixCommandCompletionStream getInstance(HystrixCommandKey commandKey) {
        HystrixCommandCompletionStream initialStream = streams.get(commandKey.name());
        if (initialStream != null) {
            return initialStream;
        } else {
            synchronized (HystrixCommandCompletionStream.class) {
                HystrixCommandCompletionStream existingStream = streams.get(commandKey.name());
                if (existingStream == null) {
                    // 创建 数据流对象
                    HystrixCommandCompletionStream newStream = new HystrixCommandCompletionStream(commandKey);
                    streams.putIfAbsent(commandKey.name(), newStream);
                    return newStream;
                } else {
                    return existingStream;
                }
            }
        }
    }

    /**
     * 创建数据流对象
     * @param commandKey
     */
    HystrixCommandCompletionStream(final HystrixCommandKey commandKey) {
        this.commandKey = commandKey;

        // subject 对象的核心作用是 将 冷的 observable 变成 热的 就是先通过 订阅 冷的 并 维护多个 订阅者 将 订阅到的冷数据 以 "热" 的方式 发射出来
        // 这样冷数据 就具备 多播的 特性  原本 每个订阅者 订阅一个observable 生成一个 订阅对象 该数据就变成它 独有 也就是单播
        // 而真正意义额 多播是一份数据被多个 订阅者共享 是存在并发问题的

        // publishSubject.create() 代表 释放 订阅后收到的所有数据  可以把 subject 理解为一个 bridge 或者 proxy 也就是 该类 一开始就是为了 将 某个 observable的数据处理后 转发给
        // 另一个 subscribe  (因为它自身可以作为一个 observable 而 自身的数据 又是靠接受其他的 observable 得到的)
        // ReplaySubject 对象 为什么能做到 将 一开始到最后的数据全部发送给新的订阅者  就是依靠缓存
        // SerializedSubject 代表 串行化  实际上就是保证线程安全 先理解为类似于一种装饰器  PublishSubject.<HystrixCommandCompletion>create() 默认创建一个 空的 Observable 但是之后可以往里面发送数据
        // 这样就会通知到下游的 订阅者  这里是从 订阅 开始 发送新的数据 而不发送旧数据
        this.writeOnlySubject = new SerializedSubject<HystrixCommandCompletion, HystrixCommandCompletion>(PublishSubject.<HystrixCommandCompletion>create());
        // 暂且理解为 该对象 只具备  subject 的 Observable 职能 就是 可以添加订阅者  不能主动修改 数据源 分发到下游的数据源应该也是由 writeOnlySubject 发出的
        this.readOnlyStream = writeOnlySubject.share();
    }

    public static void reset() {
        streams.clear();
    }

    /**
     * write 是 针对 subject 调用 onNext 方法  onNext 会 下发数据到 订阅 subject 对象的 订阅者
     * 可以这样理解  因为 onNext 是由 subject 订阅的对象发出的 而subject 又要将数据下发到 他的订阅者 所以onNext 就是往下游发送数据的方法
     * @param event
     */
    public void write(HystrixCommandCompletion event) {
        writeOnlySubject.onNext(event);
    }


    @Override
    public Observable<HystrixCommandCompletion> observe() {
        return readOnlyStream;
    }

    @Override
    public String toString() {
        return "HystrixCommandCompletionStream(" + commandKey.name() + ")";
    }
}

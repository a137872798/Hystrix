/**
 * Copyright 2012 Netflix, Inc.
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
package com.netflix.hystrix.examples.demo;

import java.net.HttpCookie;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;

/**
 * Sample HystrixCommand simulating one that would fetch UserAccount objects from a remote service or database.
 * <p>
 * This uses request caching and fallback behavior.
 * 模拟的是一个 用户请求获取 自身账号信息的命令
 */
public class GetUserAccountCommand extends HystrixCommand<UserAccount> {

    private final HttpCookie httpCookie;
    private final UserCookie userCookie;

    /**
     * 
     * @param cookie
     * @throws IllegalArgumentException
     *             if cookie is invalid meaning the user is not authenticated
     */
    public GetUserAccountCommand(HttpCookie cookie) {
        // 使用 User 作为本次 调用的 commandGroupKey  通过允许从cache 中 获取resp的方式 可以避免重复的调用
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("User")));
        this.httpCookie = cookie;
        /* parse or throw an IllegalArgumentException */
        this.userCookie = UserCookie.parseCookie(httpCookie);
    }

    @Override
    protected UserAccount run() {
        /* simulate performing network call to retrieve user information */
        try {
            Thread.sleep((int) (Math.random() * 10) + 2);
        } catch (InterruptedException e) {
            // do nothing
        }

        /* fail 5% of the time to show how fallback works */
        if (Math.random() > 0.95) {
            throw new RuntimeException("random failure processing UserAccount network response");
        }

        /* latency spike 5% of the time so timeouts can be triggered occasionally */
        if (Math.random() > 0.95) {
            // random latency spike
            try {
                Thread.sleep((int) (Math.random() * 300) + 25);
            } catch (InterruptedException e) {
                // do nothing
            }
        }

        /* success ... create UserAccount with data "from" the remote service response */
        return new UserAccount(86975, "John James", 2, true, false, true);
    }

    /**
     * Use the HttpCookie value as the cacheKey so multiple executions
     * in the same HystrixRequestContext will respond from cache.
     * 将 httpCookie 的 value 作为 缓存键对象 当调用command 时 如果开启了 缓存 且 缓存键相同 就会返回相同数据
     */
    @Override
    protected String getCacheKey() {
        return httpCookie.getValue();
    }

    /**
     * Fallback that will use data from the UserCookie and stubbed defaults
     * to create a UserAccount if the network call failed.
     * 这里是 用户代码 中自定义的 当command 执行失败时 调用的方法
     */
    @Override
    protected UserAccount getFallback() {
        /*
         * first 3 come from the HttpCookie
         * next 3 are stubbed defaults
         */
        return new UserAccount(userCookie.userId, userCookie.name, userCookie.accountType, true, true, true);
    }

    /**
     * Represents values containing in the cookie.
     * <p>
     * A real version of this could handle decrypting a secure HTTPS cookie.
     */
    private static class UserCookie {
        /**
         * Parse an HttpCookie into a UserCookie or IllegalArgumentException if invalid cookie
         * 
         * @param cookie
         * @return UserCookie
         * @throws IllegalArgumentException
         *             if cookie is invalid
         *             解析传入的 cookie 对象
         */
        private static UserCookie parseCookie(HttpCookie cookie) {
            /* real code would parse the cookie here */
            if (Math.random() < 0.998) {
                /* valid cookie */
                return new UserCookie(12345, "Henry Peter", 1);
            } else {
                /* invalid cookie */
                // 应该是模拟 99.8的成功率
                throw new IllegalArgumentException();
            }
        }

        /**
         * 代表用户信息
         * @param userId
         * @param name
         * @param accountType
         */
        public UserCookie(int userId, String name, int accountType) {
            this.userId = userId;
            this.name = name;
            this.accountType = accountType;
        }

        private final int userId;
        private final String name;
        private final int accountType;
    }
}

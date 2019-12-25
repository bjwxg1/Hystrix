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
package com.netflix.hystrix;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A group name for a {@link HystrixCommand}. This is used for grouping together commands such as for reporting, alerting, dashboards or team/library ownership.
 * <p>
 * By default this will be used to define the {@link HystrixThreadPoolKey} unless a separate one is defined.
 * <p>
 * This interface is intended to work natively with Enums so that implementing code can have an enum with the owners that implements this interface.
 */
//将HystrixCommand进行分组管理，比如报告、警报、仪表盘或组/库。
//默认情况下，Hystrix使用 HystrixCommandGroupKey 来定义命令线程池，除非单独定义线程池。
public interface HystrixCommandGroupKey {

    /**
     * The word 'name' is used instead of 'key' so that Enums can implement this interface and it work natively.
     * 
     * @return String
     */
    //分组名称
    public String name();

    public static class Factory {

        private Factory() {
        }

        //used to intern instances so we don't keep re-creating them millions of times for the same key
        //保存所有的HystrixCommandGroupKey 的Map
        private static ConcurrentHashMap<String, HystrixCommandGroupKey> intern = new ConcurrentHashMap<>();

        /**
         * Retrieve (or create) an interned HystrixCommandGroup instance for a given name.
         * 
         * @param name command group name
         * @return HystrixCommandGroup instance that is interned (cached) so a given name will always retrieve the same instance.
         */
        public static HystrixCommandGroupKey asKey(String name) {
            HystrixCommandGroupKey k = intern.get(name);
            if (k == null) {
                k = new HystrixCommandGroupDefault(name);
                intern.putIfAbsent(name, k);
            }
            return k;
        }

        private static class HystrixCommandGroupDefault implements HystrixCommandGroupKey {

            private String name;

            private HystrixCommandGroupDefault(String name) {
                this.name = name;
            }

            @Override
            public String name() {
                return name;
            }

        }

        /* package-private */ static int getGroupCount() {
            return intern.size();
        }
    }
}
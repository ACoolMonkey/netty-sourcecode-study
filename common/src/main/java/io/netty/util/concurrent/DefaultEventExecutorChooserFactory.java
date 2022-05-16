/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    private DefaultEventExecutorChooserFactory() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        /*
        这里会根据executors的长度是否是2的幂来选择调用PowerOfTwoEventExecutorChooser
        或GenericEventExecutorChooser。之所以有这个区别是因为后续会调用到next方法，
        而next方法会通过取余的方式来找到下一个EventExecutor。而如果长度为2的幂的话，会有
        优化的写法而不是使用默认的“%”取余符号，后续会详细讲解
         */
        if (isPowerOfTwo(executors.length)) {
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     * 该方法是用来判断val是否是2的幂。一个数的负数和原数的二进制形式的区别是取反再+1
     * 所以只有val是2的幂的时候，这个数和它取负数的数在最后val.length个位数长度上的
     * 数是完全一样的。所以两者按位与的结果就是这个数，以此就可以判断出来是否是2的幂了
     * 举个例子：假如说val是3，二进制是11。而-3的二进制是1111 1111 1111 1111 1111 1111 1111 1101
     * 两者在最后两位分别是11和01，是不一样的，所以按位与的结果肯定不是11，也就是十进制的3；
     * 那么假如说val是4，二进制是100；而-4的二进制是1111 1111 1111 1111 1111 1111 1111 1100
     * 两者在最后三位都是100，所以按位与的结果就是100，也就是十进制的4，也就是判断是相等的
     */
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            //这里将children赋值进PowerOfTwoEventExecutorChooser的executors
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            /*
            executors也就是之前在NioEventLoopGroup的构造器中赋值进去的children。这里就是在拿取
            executors数组中的下一个EventExecutor。因为其长度为2的幂，所以可以用按位与的优化写法
            来同样达到取余的效果（在HashMap和ConcurrentHashMap中也出现了同样的取余优化写法）
             */
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        private final AtomicInteger idx = new AtomicInteger();
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            //这里将children赋值进GenericEventExecutorChooser的executors
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            /*
            同上面的解释，这里就是当executors的长度不是2的幂的时候，使用常规的“%”取余符号来进行拿取下一个
            EventExecutor的方式。之所以这里会取绝对值是为了防止出现数据溢出的情况：当idx递增到一个特别大
            的数：2147483647，那么此时再+1就变成了-2147483648，就变成了一个负数。而数组的索引位置
            是不可能出现负数的（如果为负数就会抛出数组索引越界异常），所以在这里会做一次取绝对值的操作
             */
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}

/*
 * Copyright 2012 The Netty Project
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
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.ObjectUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers an {@link IdleStateEvent} when a {@link Channel} has not performed
 * read, write, or both operation for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr>
 * <th>Property</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code readerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
 *     will be triggered when no read was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code writerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
 *     will be triggered when no write was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code allIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *     will be triggered when neither read nor write was performed for the
 *     specified period of time.  Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 *
 * <pre>
 * // An example that sends a ping message when there is no outbound traffic
 * // for 30 seconds.  The connection is closed when there is no inbound traffic
 * // for 60 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("idleStateHandler", new {@link IdleStateHandler}(60, 30, 0));
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link IdleStateEvent} triggered by {@link IdleStateHandler}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void userEventTriggered({@link ChannelHandlerContext} ctx, {@link Object} evt) throws {@link Exception} {
 *         if (evt instanceof {@link IdleStateEvent}) {
 *             {@link IdleStateEvent} e = ({@link IdleStateEvent}) evt;
 *             if (e.state() == {@link IdleState}.READER_IDLE) {
 *                 ctx.close();
 *             } else if (e.state() == {@link IdleState}.WRITER_IDLE) {
 *                 ctx.writeAndFlush(new PingMessage());
 *             }
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 *
 * @see ReadTimeoutHandler
 * @see WriteTimeoutHandler
 */
public class IdleStateHandler extends ChannelDuplexHandler {
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    // Not create a new ChannelFutureListener per write operation to reduce GC pressure.
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            lastWriteTime = ticksInNanos();
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }
    };

    private final boolean observeOutput;
    private final long readerIdleTimeNanos;
    private final long writerIdleTimeNanos;
    private final long allIdleTimeNanos;

    private ScheduledFuture<?> readerIdleTimeout;
    private long lastReadTime;
    private boolean firstReaderIdleEvent = true;

    private ScheduledFuture<?> writerIdleTimeout;
    private long lastWriteTime;
    private boolean firstWriterIdleEvent = true;

    private ScheduledFuture<?> allIdleTimeout;
    private boolean firstAllIdleEvent = true;

    private byte state; // 0 - none, 1 - initialized, 2 - destroyed
    private boolean reading;

    private long lastChangeCheckTimeStamp;
    private int lastMessageHashCode;
    private long lastPendingWriteBytes;
    private long lastFlushProgress;

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param readerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     */
    /**
     * IdleStateHandler:
     * <1>readerIdleTimeSeconds：读超时。即当在指定的时间间隔内没有从Channel中读取到数据时，会触发一个READER_IDLE的
     * IdleStateEvent事件；
     *
     * <2>writerIdleTimeSeconds：写超时。即当在指定的时间间隔内没有数据写入到Channel时，会触发一个WRITER_IDLE的
     * IdleStateEvent事件；
     *
     * <3>allIdleTimeSeconds：读写超时。即当在指定的时间间隔内没有读或写操作时，会触发一个ALL_IDLE的IdleStateEvent事件
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * @see #IdleStateHandler(boolean, long, long, long, TimeUnit)
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param observeOutput  whether or not the consumption of {@code bytes} should be taken into
     *                       consideration when assessing write idleness. The default is {@code false}.
     * @param readerIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *                       will be triggered when no read was performed for the specified
     *                       period of time.  Specify {@code 0} to disable.
     * @param writerIdleTime an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *                       will be triggered when no write was performed for the specified
     *                       period of time.  Specify {@code 0} to disable.
     * @param allIdleTime    an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *                       will be triggered when neither read nor write was performed for
     *                       the specified period of time.  Specify {@code 0} to disable.
     * @param unit           the {@link TimeUnit} of {@code readerIdleTime},
     *                       {@code writeIdleTime}, and {@code allIdleTime}
     */
    public IdleStateHandler(boolean observeOutput,
                            long readerIdleTime, long writerIdleTime, long allIdleTime,
                            TimeUnit unit) {
        ObjectUtil.checkNotNull(unit, "unit");

        /*
        该参数用来表示是否考虑出站特别慢的情况（可能是网络传输比较慢，也可能是客户端处理比较慢）。比如出站用了10秒，
        而空闲时间是5秒，那么在数据还有没有处理完的时候，就已经触发了空闲超时事件。这明显是不合理的。但这里传进来的
        observeOutput是false，也就是不考虑这种情况（如果需要考虑这种情况的话，调用带observeOutput参数的构造器
        就行了）
         */
        this.observeOutput = observeOutput;

        //设置readerIdleTime、writerIdleTimeNanos和allIdleTimeNanos
        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * Return the readerIdleTime that was given when instance this class in milliseconds.
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * Return the writerIdleTime that was given when instance this class in milliseconds.
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * Return the allIdleTime that was given when instance this class in milliseconds.
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // channelActive() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelActive() will be invoked
            // and initialization will occur there.
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        destroy();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Initialize early if channel is active already.
        if (ctx.channel().isActive()) {
            initialize(ctx);
        }
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        //该方法会把定时任务放到eventLoop的任务队列中等待去执行
        initialize(ctx);
        //该处会调用下一个handler的channelActive方法
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            /*
            reading为true表示channelRead方法还在执行中，在channelReadComplete方法中会重置为false，
            也就是channelRead方法执行完毕了
             */
            reading = true;
            //每一次channelRead进来的时候都会将firstReaderIdleEvent和firstAllIdleEvent置为true
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        /*
        该处会调用下一个handler的channelRead方法，也就是IdleStateHandler的channelRead方法并没有做什么
        实际的事情，只是更改了一些标志位而已
         */
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            lastReadTime = ticksInNanos();
            reading = false;
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Allow writing with void promise if handler is only configured for read timeout events.
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            ctx.write(msg, promise);
        }
    }

    private void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        switch (state) {
            case 1:
            case 2:
                return;
        }

        /*
        state置为1表示已经初始化过了，同一次channelRead别处调用再进来就会在上面的代码中return了
        （为2表示已经被销毁了，也直接退出）
         */
        state = 1;
        //如果observeOutput设置为true的话，需要记录一些出站缓冲区的相关信息，以考虑出站特别慢的情况
        initOutputChanged(ctx);

        //重置lastReadTime和lastWriteTime为当前时间
        lastReadTime = lastWriteTime = ticksInNanos();
        //如果readerIdleTimeNanos>0，就会把定时任务加到scheduledTaskQueue中
        if (readerIdleTimeNanos > 0) {
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                    readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        //如果writerIdleTimeNanos>0，就会把定时任务加到scheduledTaskQueue中
        if (writerIdleTimeNanos > 0) {
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                    writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        //如果allIdleTimeNanos>0，就会把定时任务加到scheduledTaskQueue中
        if (allIdleTimeNanos > 0) {
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                    allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * This method is visible for testing!
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        /*
        可以看到这里也是调用了schedule方法。之前已经分析过了，该方法会把任务放进scheduledTaskQueue中，
        后续再去等线程执行runAllTasks方法，把任务拿出来再执行
         */
        return ctx.executor().schedule(task, delay, unit);
    }

    private void destroy() {
        state = 2;

        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * @see #hasOutputChanged(ChannelHandlerContext, boolean)
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            /*
            这里会记录一些出站缓冲区的相关信息（缓冲对象的hashCode、buf剩余待写的字节数和当前信息的刷新进度），
            为了后面的hasOutputChanged方法做判断用
             */
            if (buf != null) {
                lastMessageHashCode = System.identityHashCode(buf.current());
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
                lastFlushProgress = buf.currentProgress();
            }
        }
    }

    /**
     * Returns {@code true} if and only if the {@link IdleStateHandler} was constructed
     * with {@link #observeOutput} enabled and there has been an observed change in the
     * {@link ChannelOutboundBuffer} between two consecutive calls of this method.
     * <p>
     * https://github.com/netty/netty/issues/6150
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        //如果observeOutput为false，也就是不考虑出站特别慢，就直接返回false
        if (observeOutput) {

            // We can take this shortcut if the ChannelPromises that got passed into write()
            // appear to complete. It indicates "change" on message level and we simply assume
            // that there's change happening on byte level. If the user doesn't observe channel
            // writability events then they'll eventually OOME and there's clearly a different
            // problem and idleness is least of their concerns.
            /*
            如果最后记录的时间和上一次记录的不同，说明此时是第一次进入该方法，或者可能是写操作已经完成了，
            更新了lastWriteTime。此时重新更新一下lastChangeCheckTimeStamp
             */
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // But this applies only if it's the non-first call.
                /*
                如果在出站处理完成之前又触发了下一次的定时任务，从而走到这里的话，first就是false
                也就是说在定时任务两次调用hasOutputChanged方法的过程中，出站事件一直在处理中、没有执行
                完毕。那么此时就直接返回true，后面也不会再处理userEventTriggered方法，直到出站结束
                 */
                if (!first) {
                    return true;
                }
            }

            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                //如果出站缓冲区有数据的话，说明之前在initOutputChanged方法中记录了这些信息，此时再次获取这些信息进行比对
                int messageHashCode = System.identityHashCode(buf.current());
                long pendingWriteBytes = buf.totalPendingWriteBytes();

                //如果不等，说明缓冲区的数据在慢慢发生变化，也就是出站特别慢的情况。此时重新更新一下这些值
                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;

                    /*
                    走到这里还是要判断一下first的值，如果为false（不是第一次调用），就不会触发下一个handler的
                    userEventTriggered方法；如果为true（第一次调用），就会触发。按照原先设想的逻辑：如果发现
                    出站特别慢，那么此时就不应该去触发userEventTriggered方法，静静等待出站完毕就好了。那么为什么
                    第一次调用的时候需要触发呢？其实这里是Netty设计者的考虑。如果真的是发生了出站特别慢的情况，
                    很可能会引发后续的OOM。所以这里第一次调用会触发userEventTriggered方法，从而给使用者一个警告
                    如果这里不触发，从而最终可能发生OOM的话，可能会花费很大的功夫才能找到原来是出站特别慢（可能是
                    网络传输比较慢，也可能是客户端处理比较慢）才导致的OOM
                     */
                    if (!first) {
                        return true;
                    }
                }

                /*
                lastFlushProgress属性是4.1.x版本中新加的，之前4.0.x版本中没有这个属性。添加的原因详见
                https://github.com/netty/netty/commit/51112e2b36ec5550a73d72bfc59f4523f7b8ec27
                 */
                long flushProgress = buf.currentProgress();
                if (flushProgress != lastFlushProgress) {
                    lastFlushProgress = flushProgress;

                    //同上面的解释
                    if (!first) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private abstract static class AbstractIdleTask implements Runnable {

        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            if (!ctx.channel().isOpen()) {
                return;
            }

            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * ReaderIdleTimeoutTask的run方法，定时任务最终会走到这里
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {
            long nextDelay = readerIdleTimeNanos;
            if (!reading) {
                /*
                ticksInNanos方法是用来获取当前时间，减去上次channelRead的时间表示的就是距离上次channelRead
                后已经过了多久了。这时候用设置好的read超时时间再去减的话，结果就是下次的超时时间（注意：这一步是发生在
                reading为false，也就是channelRead方法执行完毕的时候）
                 */
                nextDelay -= ticksInNanos() - lastReadTime;
            }

            if (nextDelay <= 0) {
                // Reader is idle - set a new timeout and notify the callback.
                /*
                如上面所说，如果nextDelay<=0就说明已经到达或超过了超时时间。此时再次添加心跳任务进行下一次的心跳检测
                从这里可以看出，Netty的心跳定时任务是通过循环调用schedule方法来添加任务，然后判断当前时间差来实现的
                 */
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstReaderIdleEvent;
                //firstReaderIdleEvent重新置为false
                firstReaderIdleEvent = false;

                try {
                    //构建一个READER_IDLE事件交给下面的userEventTriggered方法
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    /*
                    执行下一个handler的userEventTriggered方法（也就是我们必须要实现的方法）来处理超时
                    （该方法中就一行代码：ctx.fireUserEventTriggered(evt);）
                     */
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Read occurred before the timeout - set a new timeout with shorter delay.
                /*
                如上面所说，如果nextDelay>0就说明此时还没有超时，那么就会把新的定时任务加到scheduledTaskQueue中
                此时nextDelay为更新过的超时时间
                 */
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * WriterIdleTimeoutTask的run方法，定时任务最终会走到这里
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {

            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            //和ReaderIdleTimeoutTask的run方法一样，更新一下此时的nextDelay
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);
            if (nextDelay <= 0) {
                // Writer is idle - set a new timeout and notify the callback.
                //如果nextDelay<=0就说明已经到达或超过了超时时间。此时再次添加心跳任务进行下一次的心跳检测
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstWriterIdleEvent;
                //firstWriterIdleEvent重新置为false
                firstWriterIdleEvent = false;

                try {
                    //考虑出站特别慢的情况
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    //构建一个WRITER_IDLE事件交给下面的userEventTriggered方法
                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    //执行下一个handler的userEventTriggered方法（也就是我们必须要实现的方法）来处理超时
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Write occurred before the timeout - set a new timeout with shorter delay.
                /*
                如果nextDelay>0就说明此时还没有超时，那么就会把新的定时任务加到scheduledTaskQueue中
                此时nextDelay为更新过的超时时间
                 */
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * AllIdleTimeoutTask的run方法，定时任务最终会走到这里
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {

            long nextDelay = allIdleTimeNanos;
            if (!reading) {
                /*
                同理，更新一下此时的nextDelay（注意：这一步是发生在reading为false，也就是channelRead方法
                执行完毕的时候）
                 */
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime);
            }
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                //如果nextDelay<=0就说明已经到达或超过了超时时间。此时再次添加心跳任务进行下一次的心跳检测
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstAllIdleEvent;
                //firstAllIdleEvent重新置为false
                firstAllIdleEvent = false;

                try {
                    //考虑出站特别慢的情况
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    //构建一个ALL_IDLE事件交给下面的userEventTriggered方法
                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    //执行下一个handler的userEventTriggered方法（也就是我们必须要实现的方法）来处理超时
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                /*
                如果nextDelay>0就说明此时还没有超时，那么就会把新的定时任务加到scheduledTaskQueue中
                此时nextDelay为更新过的超时时间
                 */
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}

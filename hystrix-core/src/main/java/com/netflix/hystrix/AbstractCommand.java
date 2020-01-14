/**
 * Copyright 2013 Netflix, Inc.
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
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCircuitBreaker.NoOpCircuitBreaker;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.exception.HystrixTimeoutException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategy;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import com.netflix.hystrix.util.HystrixTimer;
import com.netflix.hystrix.util.HystrixTimer.TimerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Notification;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.ReplaySubject;
import rx.subscriptions.CompositeSubscription;

import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

abstract class AbstractCommand<R> implements HystrixInvokableInfo<R>, HystrixObservable<R> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractCommand.class);
    //熔断器
    protected final HystrixCircuitBreaker circuitBreaker;
    //线程池隔离时使用的线程池
    protected final HystrixThreadPool threadPool;
    //线程池对应的Key
    protected final HystrixThreadPoolKey threadPoolKey;
    //HystrixCommand 配置
    protected final HystrixCommandProperties properties;

    //超时状态
    protected static enum TimedOutStatus {
        NOT_EXECUTED, COMPLETED, TIMED_OUT
    }

    //HystrixCommand的统计指标
    protected final HystrixCommandMetrics metrics;

    //HystrixCommandKey
    protected final HystrixCommandKey commandKey;
    //HystrixCommandGroupKey
    protected final HystrixCommandGroupKey commandGroup;

    /**
     * Plugin implementations TODO
     */
    protected final HystrixEventNotifier eventNotifier;
    //HystrixConcurrencyStrategy[一些创建线程池时使用的策略]
    protected final HystrixConcurrencyStrategy concurrencyStrategy;
    //
    // HystrixCommandExecutionHook TODO
    protected final HystrixCommandExecutionHook executionHook;

    /* FALLBACK Semaphore */
    //降级处理的并发控制
    protected final TryableSemaphore fallbackSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> fallbackSemaphorePerCircuit = new ConcurrentHashMap<>();
    /* END FALLBACK Semaphore */

    /* EXECUTION Semaphore */
    //信号量隔离时使用的信号量
    protected final TryableSemaphore executionSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> executionSemaphorePerCircuit = new ConcurrentHashMap<>();
    /* END EXECUTION Semaphore */

    //TimerListener 主要用于判断Command是否超时。TimerListener在HystrixTimer中执行
    protected final AtomicReference<Reference<TimerListener>> timeoutTimer = new AtomicReference<>();
    //启动标识
    protected AtomicBoolean started = new AtomicBoolean();

    /* result of execution (if this command instance actually gets executed, which may not occur due to request caching) */
    //执行结果
    protected volatile ExecutionResult executionResult = ExecutionResult.EMPTY;

    /* If this command executed and timed-out */
    //超时状态
    protected final AtomicReference<TimedOutStatus> isCommandTimedOut = new AtomicReference<>(TimedOutStatus.NOT_EXECUTED);
    //任务完成标识
    protected final AtomicBoolean isExecutionComplete = new AtomicBoolean(false);
    //Command执行完时的执行方法 TODO
    protected final AtomicReference<Action0> endCurrentThreadExecutingCommand = new AtomicReference<>(); // don't like how this is being done

    /**
     * Instance of RequestCache logic
     */
    protected final HystrixRequestCache requestCache;
    protected final HystrixRequestLog currentRequestLog;

    // this is a micro-optimization but saves about 1-2microseconds (on 2011 MacBook Pro) 
    // on the repetitive string processing that will occur on the same classes over and over again
    private static ConcurrentHashMap<Class<?>, String> defaultNameCache = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<HystrixCommandKey, Boolean> commandContainsFallback = new ConcurrentHashMap<>();

    static String getDefaultNameFromClass(Class<?> cls) {
        String fromCache = defaultNameCache.get(cls);
        if (fromCache != null) {
            return fromCache;
        }
        // generate the default
        // default HystrixCommandKey to use if the method is not overridden
        String name = cls.getSimpleName();
        if (name.equals("")) {
            // we don't have a SimpleName (anonymous inner class) so use the full class name
            name = cls.getName();
            name = name.substring(name.lastIndexOf('.') + 1, name.length());
        }
        defaultNameCache.put(cls, name);
        return name;
    }



    protected AbstractCommand(HystrixCommandGroupKey group, HystrixCommandKey key, HystrixThreadPoolKey threadPoolKey, HystrixCircuitBreaker circuitBreaker, HystrixThreadPool threadPool,
            HystrixCommandProperties.Setter commandPropertiesDefaults, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults,
            HystrixCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
            HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {
        //HystrixCommandGroupKey不能为空
        this.commandGroup = initGroupKey(group);
        //如果HystrixCommandKey为空，默认使用className
        this.commandKey = initCommandKey(key, getClass());
        this.properties = initCommandProperties(this.commandKey, propertiesStrategy, commandPropertiesDefaults);
        this.threadPoolKey = initThreadPoolKey(threadPoolKey, this.commandGroup, this.properties.executionIsolationThreadPoolKeyOverride().get());
        //初始化metrics
        this.metrics = initMetrics(metrics, this.commandGroup, this.threadPoolKey, this.commandKey, this.properties);
        //初始化circuitBreaker
        this.circuitBreaker = initCircuitBreaker(this.properties.circuitBreakerEnabled().get(), circuitBreaker, this.commandGroup, this.commandKey, this.properties, this.metrics);
        //初始化threadPool
        this.threadPool = initThreadPool(threadPool, this.threadPoolKey, threadPoolPropertiesDefaults);


        //Strategies from plugins
        this.eventNotifier = HystrixPlugins.getInstance().getEventNotifier();
        this.concurrencyStrategy = HystrixPlugins.getInstance().getConcurrencyStrategy();
        HystrixMetricsPublisherFactory.createOrRetrievePublisherForCommand(this.commandKey, this.commandGroup, this.metrics, this.circuitBreaker, this.properties);
        this.executionHook = initExecutionHook(executionHook);

        this.requestCache = HystrixRequestCache.getInstance(this.commandKey, this.concurrencyStrategy);
        this.currentRequestLog = initRequestLog(this.properties.requestLogEnabled().get(), this.concurrencyStrategy);

        /* fallback semaphore override if applicable */
        this.fallbackSemaphoreOverride = fallbackSemaphore;

        /* execution semaphore override if applicable */
        this.executionSemaphoreOverride = executionSemaphore;
    }

    private static HystrixCommandGroupKey initGroupKey(final HystrixCommandGroupKey fromConstructor) {
        if (fromConstructor == null) {
            throw new IllegalStateException("HystrixCommandGroup can not be NULL");
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCommandKey initCommandKey(final HystrixCommandKey fromConstructor, Class<?> clazz) {
        if (fromConstructor == null || fromConstructor.name().trim().equals("")) {
            final String keyName = getDefaultNameFromClass(clazz);
            return HystrixCommandKey.Factory.asKey(keyName);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCommandProperties initCommandProperties(HystrixCommandKey commandKey, HystrixPropertiesStrategy propertiesStrategy, HystrixCommandProperties.Setter commandPropertiesDefaults) {
        if (propertiesStrategy == null) {
            return HystrixPropertiesFactory.getCommandProperties(commandKey, commandPropertiesDefaults);
        } else {
            // used for unit testing
            return propertiesStrategy.getCommandProperties(commandKey, commandPropertiesDefaults);
        }
    }

    /*
     * ThreadPoolKey
     *
     * This defines which thread-pool this command should run on.
     *
     * It uses the HystrixThreadPoolKey if provided, then defaults to use HystrixCommandGroup.
     *
     * It can then be overridden by a property if defined so it can be changed at runtime.
     */
    private static HystrixThreadPoolKey initThreadPoolKey(HystrixThreadPoolKey threadPoolKey, HystrixCommandGroupKey groupKey, String threadPoolKeyOverride) {
        if (threadPoolKeyOverride == null) {
            // we don't have a property overriding the value so use either HystrixThreadPoolKey or HystrixCommandGroup
            if (threadPoolKey == null) {
                /* use HystrixCommandGroup if HystrixThreadPoolKey is null */
                return HystrixThreadPoolKey.Factory.asKey(groupKey.name());
            } else {
                return threadPoolKey;
            }
        } else {
            // we have a property defining the thread-pool so use it instead
            return HystrixThreadPoolKey.Factory.asKey(threadPoolKeyOverride);
        }
    }

    private static HystrixCommandMetrics initMetrics(HystrixCommandMetrics fromConstructor, HystrixCommandGroupKey groupKey,
                                                     HystrixThreadPoolKey threadPoolKey, HystrixCommandKey commandKey,
                                                     HystrixCommandProperties properties) {
        if (fromConstructor == null) {
            return HystrixCommandMetrics.getInstance(commandKey, groupKey, threadPoolKey, properties);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCircuitBreaker initCircuitBreaker(boolean enabled, HystrixCircuitBreaker fromConstructor,
                                                            HystrixCommandGroupKey groupKey, HystrixCommandKey commandKey,
                                                            HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
        if (enabled) {
            if (fromConstructor == null) {
                // get the default implementation of HystrixCircuitBreaker
                return HystrixCircuitBreaker.Factory.getInstance(commandKey, groupKey, properties, metrics);
            } else {
                return fromConstructor;
            }
        } else {
            return new NoOpCircuitBreaker();
        }
    }

    private static HystrixCommandExecutionHook initExecutionHook(HystrixCommandExecutionHook fromConstructor) {
        if (fromConstructor == null) {
            return HystrixPlugins.getInstance().getCommandExecutionHook();
        } else {
            // used for unit testing
            return fromConstructor;
        }
    }

    private static HystrixThreadPool initThreadPool(HystrixThreadPool fromConstructor, HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
        if (fromConstructor == null) {
            // get the default implementation of HystrixThreadPool
            return HystrixThreadPool.Factory.getInstance(threadPoolKey, threadPoolPropertiesDefaults);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixRequestLog initRequestLog(boolean enabled, HystrixConcurrencyStrategy concurrencyStrategy) {
        if (enabled) {
            /* store reference to request log regardless of which thread later hits it */
            return HystrixRequestLog.getCurrentRequest(concurrencyStrategy);
        } else {
            return null;
        }
    }

    /**
     * Allow the Collapser to mark this command instance as being used for a collapsed request and how many requests were collapsed.
     * 
     * @param sizeOfBatch number of commands in request batch
     */
    /* package */void markAsCollapsedCommand(HystrixCollapserKey collapserKey, int sizeOfBatch) {
        eventNotifier.markEvent(HystrixEventType.COLLAPSED, this.commandKey);
        executionResult = executionResult.markCollapsed(collapserKey, sizeOfBatch);
    }

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This eagerly starts execution of the command the same as {@link HystrixCommand#queue()} and {@link HystrixCommand#execute()}.
     * <p>
     * A lazy {@link Observable} can be obtained from {@link #toObservable()}.
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> observe() {
        // us a ReplaySubject to buffer the eagerly subscribed-to Observable
        ReplaySubject<R> subject = ReplaySubject.create();
        // eagerly kick off subscription
        toObservable().subscribe(subject);
        // return the subject that can be subscribed to later while the execution has already started
        return subject;
    }

    protected abstract Observable<R> getExecutionObservable();

    protected abstract Observable<R> getFallbackObservable();

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This lazily starts execution of the command once the {@link Observable} is subscribed to.
     * <p>
     * An eager {@link Observable} can be obtained from {@link #observe()}.
     * <p>
     * See https://github.com/ReactiveX/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> toObservable() {
        //判断是否已经启动，如果没有直接返回
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This instance can only be executed once. Please instantiate a new instance.");
        }

        final HystrixInvokableInfo<R> _this = this;
        final boolean requestCacheEnabled = isRequestCachingEnabled();
        //如果允许cache，尝试从cache中获取
        if (requestCacheEnabled) {
            //从RequestCache中获取响应（Observable）
            Observable<R> fromCache = requestCache.get(getCacheKey());
            if (fromCache != null) {
                long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                executionResult = executionResult.markUserThreadCompletion((int) latency);
                executionResult = executionResult.addEvent((int) latency, HystrixEventType.RESPONSE_FROM_CACHE);
                //向metrics发送统计信息
                metrics.markCommandDone(executionResult, commandKey, threadPoolKey);
                eventNotifier.markEvent(HystrixEventType.RESPONSE_FROM_CACHE, commandKey);
                isExecutionComplete.set(true);
                try {
                    executionHook.onCacheHit(this);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onCacheHit", hookEx);
                }
                return new CachedObservableResponse<>((CachedObservableOriginal<R>) fromCache, this);
            }
        }

        // create an Observable that will lazily execute when subscribed to
        //创建Observable
        Observable<R> o = Observable.create(new OnSubscribe<R>() {
            @Override
            public void call(Subscriber<? super R> observer) {
                // async record keeping
                recordExecutedCommand();

                // mark that we're starting execution on the ExecutionHook
                // if this hook throws an exception, then a fast-fail occurs with no fallback.  No state is left inconsistent
                executionHook.onStart(_this);

                /* determine if we're allowed to execute */
                //判断断路器是否允许命令通过
                if (circuitBreaker.allowRequest()) {
                    final TryableSemaphore executionSemaphore = getExecutionSemaphore();
                    // acquire a permit
                    //获取信号量
                    if (executionSemaphore.tryAcquire()) {
                        try {
                            /* used to track userThreadExecutionTime */
                            //设置执行开始时间
                            executionResult = executionResult.setInvocationStartTime(System.currentTimeMillis());
                            getRunObservableDecoratedForMetricsAndErrorHandling()
                                    .doOnTerminate(executionSemaphore::release).unsafeSubscribe(observer);
                        } catch (RuntimeException e) {
                            observer.onError(e);
                        }
                    } else {
                        //如果获取信号量失败
                        Exception semaphoreRejectionException = new RuntimeException("could not acquire a semaphore for execution");
                        executionResult = executionResult.setExecutionException(semaphoreRejectionException);
                        eventNotifier.markEvent(HystrixEventType.SEMAPHORE_REJECTED, commandKey);
                        logger.debug("HystrixCommand Execution Rejection by Semaphore."); // debug only since we're throwing the exception and someone higher will do something with it
                        // retrieve a fallback or throw an exception if no fallback available
                        //获取fallBack或者抛出异常
                        getFallbackOrThrowException(HystrixEventType.SEMAPHORE_REJECTED, FailureType.REJECTED_SEMAPHORE_EXECUTION,
                                "could not acquire a semaphore for execution", semaphoreRejectionException)
                                .unsafeSubscribe(observer);
                    }
                }
                //断路处理逻辑【断路器已经打开，直接走fallback进行降级处理】
                else {
                    // record that we are returning a short-circuited fallback
                    eventNotifier.markEvent(HystrixEventType.SHORT_CIRCUITED, commandKey);
                    // short-circuit and go directly to fallback (or throw an exception if no fallback implemented)
                    Exception shortCircuitException = new RuntimeException("Hystrix circuit short-circuited and is OPEN");
                    executionResult = executionResult.setExecutionException(shortCircuitException);
                    try {
                        //获取fallBack方法，【执行fallBack相关逻辑处理】
                        getFallbackOrThrowException(HystrixEventType.SHORT_CIRCUITED, FailureType.SHORTCIRCUIT,
                                "short-circuited", shortCircuitException)
                                .unsafeSubscribe(observer);
                    } catch (Exception e) {
                        observer.onError(e);
                    }
                }
            }
        });

        //apply all lifecycle hooks
        o = o.lift(new CommandHookApplication(this));

        // error handling at very end (this means fallback didn't exist or failed)
        o = o.onErrorResumeNext(t -> {
            // count that we are throwing an exception and re-throw it
            eventNotifier.markEvent(HystrixEventType.EXCEPTION_THROWN, commandKey);
            return Observable.error(t);
        });

        // any final cleanup needed
        o = o.doOnTerminate(() -> {
            Reference<TimerListener> tl = timeoutTimer.get();
            if (tl != null) {
                tl.clear();
            }

            //用户线程延迟
            long userThreadLatency = System.currentTimeMillis() - executionResult.getStartTimestamp();
            executionResult = executionResult.markUserThreadCompletion((int) userThreadLatency);
            metrics.markCommandDone(executionResult, commandKey, threadPoolKey);
            // record that we're completed
            isExecutionComplete.set(true);
        });

        // put in cache
        if (requestCacheEnabled) {
            // wrap it for caching
            o = new CachedObservableOriginal<>(o.cache(), this);
            Observable<R> fromCache = requestCache.putIfAbsent(getCacheKey(), o);
            if (fromCache != null) {
                // another thread beat us so we'll use the cached value instead
                o = new CachedObservableResponse<>((CachedObservableOriginal<R>) fromCache, this);
            }
            // we just created an ObservableCommand so we cast and return it
            return o;
        } else {
            // no request caching so a simple wrapper just to pass 'this' along with the Observable
            return new ObservableCommand<>(o, this);
        }
    }

    /**
     * This decorate "Hystrix" functionality around the run() Observable.
     * 
     * @return R
     */
    private Observable<R> getRunObservableDecoratedForMetricsAndErrorHandling() {
        final AbstractCommand<R> _self = this;

        final HystrixRequestContext currentRequestContext = HystrixRequestContext.getContextForCurrentThread();

        Observable<R> run;
        //判断是否是线程池隔离
        if (properties.executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD)) {
            // mark that we are executing in a thread (even if we end up being rejected we still were a THREAD execution and not SEMAPHORE
            //创建Observable
            run = Observable.create(new OnSubscribe<R>() {
                @Override
                public void call(Subscriber<? super R> s) {
                    //metrics创建并发送CommandStart事件
                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.THREAD);
                    //判断命令执行是否超时，如果已经超时，发送ERROR事件
                    if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                        // the command timed out in the wrapping thread so we will return immediately
                        // and not increment any of the counters below or other such logic
                        s.onError(new RuntimeException("timed out before executing run()"));
                    } else {
                        // not timed out so execute
                        //增加并发线程数
                        HystrixCounters.incrementGlobalConcurrentThreads();
                        threadPool.markThreadExecution();
                        // store the command that is being run
                        endCurrentThreadExecutingCommand.set(Hystrix.startCurrentThreadExecutingCommand(getCommandKey()));
                        executionResult = executionResult.setExecutedInThread();
                        /**
                         * If any of these hooks throw an exception, then it appears as if the actual execution threw an error
                         */
                        try {
                            executionHook.onThreadStart(_self);
                            executionHook.onExecutionStart(_self);
                            //
                            getExecutionObservableWithLifecycle().unsafeSubscribe(s);
                        } catch (Throwable ex) {
                            s.onError(ex);
                        }
                    }
                }
            }).subscribeOn(threadPool.getScheduler(() -> properties.executionIsolationThreadInterruptOnTimeout().get() && _self.isCommandTimedOut.get().equals(TimedOutStatus.TIMED_OUT)));
        }
        //
        else {
            metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.SEMAPHORE);
            // semaphore isolated
            // store the command that is being run
            endCurrentThreadExecutingCommand.set(Hystrix.startCurrentThreadExecutingCommand(getCommandKey()));
            try {
                executionHook.onExecutionStart(_self);
                run = getExecutionObservableWithLifecycle();  //the getExecutionObservableWithLifecycle method already wraps sync exceptions, so this shouldn't throw
            } catch (Throwable ex) {
                //If the above hooks throw, then use that as the result of the run method
                run = Observable.error(ex);
            }
        }

        run = run.doOnEach(n -> {
            setRequestContextIfNeeded(currentRequestContext);
        });
        if (properties.executionTimeoutEnabled().get()) {
            run = run.lift(new HystrixObservableTimeoutOperator<>(_self));
        }
        run = run.doOnNext(r -> {
            if (shouldOutputOnNextEvents()) {
                executionResult = executionResult.addEvent(HystrixEventType.EMIT);
                eventNotifier.markEvent(HystrixEventType.EMIT, commandKey);
            }
        }).doOnCompleted(() -> {
            long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
            eventNotifier.markEvent(HystrixEventType.SUCCESS, commandKey);
            executionResult = executionResult.addEvent((int) latency, HystrixEventType.SUCCESS);
            circuitBreaker.markSuccess();
            eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
        }).onErrorResumeNext(t -> {
            Exception e = getExceptionFromThrowable(t);
            executionResult = executionResult.setExecutionException(e);
            if (e instanceof RejectedExecutionException) {
                /**
                 * Rejection handling
                 */
                eventNotifier.markEvent(HystrixEventType.THREAD_POOL_REJECTED, commandKey);
                threadPool.markThreadRejection();
                // use a fallback instead (or throw exception if not implemented)
                return getFallbackOrThrowException(HystrixEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "could not be queued for execution", e);
            } else if (t instanceof HystrixTimeoutException) {
                /**
                 * Timeout handling
                 *
                 * Callback is performed on the HystrixTimer thread.
                 */
                return getFallbackOrThrowException(HystrixEventType.TIMEOUT, FailureType.TIMEOUT, "timed-out", new TimeoutException());
            } else if (t instanceof HystrixBadRequestException) {
                /**
                 * BadRequest handling
                 */
                try {
                    long executionLatency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                    executionResult = executionResult.addEvent((int) executionLatency, HystrixEventType.BAD_REQUEST);
                    Exception decorated = executionHook.onError(_self, FailureType.BAD_REQUEST_EXCEPTION, (Exception) t);

                    if (decorated instanceof HystrixBadRequestException) {
                        t = decorated;
                    } else {
                        logger.warn("ExecutionHook.onError returned an exception that was not an instance of HystrixBadRequestException so will be ignored.", decorated);
                    }
                } catch (Exception hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
                }
                /*
                 * HystrixBadRequestException is treated differently and allowed to propagate without any stats tracking or fallback logic
                 */
                return Observable.error(t);
            } else {

                /**
                 * All other error handling
                 */
                logger.debug("Error executing HystrixCommand.run(). Proceeding to fallback logic ...", e);

                // report failure
                eventNotifier.markEvent(HystrixEventType.FAILURE, commandKey);

                // record the exception
                executionResult = executionResult.setException(e);
                return getFallbackOrThrowException(HystrixEventType.FAILURE, FailureType.COMMAND_EXCEPTION, "failed", e);
            }
        }).doOnEach(n -> {
            setRequestContextIfNeeded(currentRequestContext);
        }).doOnTerminate(() -> {
            //if the command timed out, then we've reached this point in the calling thread
            //but the Hystrix thread is still doing work.  Let it handle these markers.
            if (!isCommandTimedOut.get().equals(TimedOutStatus.TIMED_OUT)) {
                handleThreadEnd();
            }
        });

        return run;
    }

    private Observable<R> getExecutionObservableWithLifecycle() {
        final HystrixInvokableInfo<R> _self = this;
        Observable<R> userObservable;
        try {
            //获取执行run()方法的Observable
            userObservable = getExecutionObservable();
        } catch (Throwable ex) {
            // the run() method is a user provided implementation so can throw instead of using Observable.onError
            // so we catch it here and turn it into Observable.error
            userObservable = Observable.error(ex);
        }
        return userObservable.lift(new ExecutionHookApplication(_self))
                .doOnTerminate(() -> {
                    //If the command timed out, then the calling thread has already walked away so we need
                    //to handle these markers.  Otherwise, the calling thread will perform these for us.
                    //超时逻辑处理
                    if (isCommandTimedOut.get().equals(TimedOutStatus.TIMED_OUT)) {
                        handleThreadEnd();
                    }
                });
    }

    /**
     * Execute <code>getFallback()</code> within protection of a semaphore that limits number of concurrent executions.
     * <p>
     * Fallback implementations shouldn't perform anything that can be blocking, but we protect against it anyways in case someone doesn't abide by the contract.
     * <p>
     * If something in the <code>getFallback()</code> implementation is latent (such as a network call) then the semaphore will cause us to start rejecting requests rather than allowing potentially
     * all threads to pile up and block.
     *
     * @return K
     * @throws UnsupportedOperationException
     *             if getFallback() not implemented
     * @throws HystrixRuntimeException
     *             if getFallback() fails (throws an Exception) or is rejected by the semaphore
     */
    //在信号量的保护下执行fallBack逻辑
    private Observable<R> getFallbackOrThrowException(final HystrixEventType eventType,
                                                      final FailureType failureType, final String message, final Exception originalException) {
        final HystrixRequestContext currentRequestContext = HystrixRequestContext.getContextForCurrentThread();
        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
        executionResult = executionResult.addEvent((int) latency, eventType);
        Observable<R> fallbackLogicApplied;
        //判断是否值可恢复异常
        if (isUnrecoverable(originalException)) {
            Exception e = originalException;
            logger.error("Unrecoverable Error for HystrixCommand so will throw HystrixRuntimeException and not apply fallback. ", e);
            /* executionHook for all errors */
            e = wrapWithOnErrorHook(failureType, e);
            if (e instanceof HystrixBadRequestException) {
                /*
                 * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                 */
                eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                executionResult.addEvent(HystrixEventType.BAD_REQUEST);
                fallbackLogicApplied = Observable.error(e);
            } else {
                fallbackLogicApplied = Observable.<R>error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and encountered unrecoverable error.", e, null));
            }
        } else {
            if (isRecoverableError(originalException)) {
                logger.warn("Recovered from java.lang.Error by serving Hystrix fallback", originalException);
            }

            if (properties.fallbackEnabled().get()) {
                /* fallback behavior is permitted so attempt */
                final AbstractCommand<R> _cmd = this;
                //获取执行fallBack的信号量
                final TryableSemaphore fallbackSemaphore = getFallbackSemaphore();
                Observable<R> fallbackExecutionChain;
                // 尝试获取fallbackSemaphore【为什么在进行fallback方法时也需要获取信号量。为了防止大量的断路或者失败同时执行fallback消耗系统资源】
                if (fallbackSemaphore.tryAcquire()) {
                    try {
                        //判断是否实现了fallback方法
                        if (isFallbackUserSupplied(this)) {
                            executionHook.onFallbackStart(this);
                            //获取FallbackObservable【在FallbackObservable里面会调动重载的fallback方法】
                            fallbackExecutionChain = getFallbackObservable();
                        } else {
                            fallbackExecutionChain = getFallbackObservable();
                        }
                    } catch(Throwable ex) {
                        fallbackExecutionChain = Observable.error(ex);
                    }
                    fallbackExecutionChain =  fallbackExecutionChain
                            .lift(new FallbackHookApplication(_cmd))
                            .doOnTerminate(fallbackSemaphore::release);//释放fallbackSemaphore
                }
                //如果没有获取到fallbackSemaphore
                else {
                    long latencyWithFallback = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.FALLBACK_REJECTION, commandKey);
                    executionResult = executionResult.addEvent((int) latencyWithFallback, HystrixEventType.FALLBACK_REJECTION);
                    // if we couldn't acquire a permit, we "fail fast" by throwing an exception
                    return Observable.error(new HystrixRuntimeException(FailureType.REJECTED_SEMAPHORE_FALLBACK,
                            this.getClass(), getLogMessagePrefix() + " fallback execution rejected.", null, null));
                }

                fallbackLogicApplied = fallbackExecutionChain.doOnNext(r -> {
                    if (shouldOutputOnNextEvents()) {
                        executionResult = executionResult.addEvent(HystrixEventType.FALLBACK_EMIT);
                        eventNotifier.markEvent(HystrixEventType.FALLBACK_EMIT, commandKey);
                    }
                }).doOnCompleted(() -> {
                    long latency1 = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    // mark fallback on counter
                    eventNotifier.markEvent(HystrixEventType.FALLBACK_SUCCESS, commandKey);
                    // record the executionResult
                    executionResult = executionResult.addEvent((int) latency1, HystrixEventType.FALLBACK_SUCCESS);
                }).onErrorResumeNext(t -> {
                    Exception e = originalException;
                    Exception fe = getExceptionFromThrowable(t);

                    if (fe instanceof UnsupportedOperationException) {
                        long latency1 = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        logger.debug("No fallback for HystrixCommand. ", fe); // debug only since we're throwing the exception and someone higher will do something with it
                        eventNotifier.markEvent(HystrixEventType.FALLBACK_MISSING, commandKey);
                        executionResult = executionResult.addEvent((int) latency1, HystrixEventType.FALLBACK_MISSING);

                        /* executionHook for all errors */
                        e = wrapWithOnErrorHook(failureType, e);
                        if (e instanceof HystrixBadRequestException) {
                            /*
                             * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                             */
                            eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                            executionResult = executionResult.addEvent(HystrixEventType.BAD_REQUEST);
                            return Observable.error(e);
                        }

                        return Observable.error(new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and no fallback available.", e, fe));
                    } else {
                        long latency1 = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        logger.debug("HystrixCommand execution " + failureType.name() + " and fallback failed.", fe);
                        eventNotifier.markEvent(HystrixEventType.FALLBACK_FAILURE, commandKey);
                        // record the executionResult
                        executionResult = executionResult.addEvent((int) latency1, HystrixEventType.FALLBACK_FAILURE);

                        /* executionHook for all errors */
                        e = wrapWithOnErrorHook(failureType, e);
                        if (e instanceof HystrixBadRequestException) {
                            /*
                             * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                             */
                            eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                            executionResult = executionResult.addEvent(HystrixEventType.BAD_REQUEST);
                            return Observable.error(e);
                        }

                        return Observable.error(new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and fallback failed.", e, fe));
                    }
                }).doOnTerminate(() -> {
                    // record that we're completed (to handle non-successful events we do it here as well as at the end of executeCommand
                    isExecutionComplete.set(true);
                }).doOnEach(n -> {
                    setRequestContextIfNeeded(currentRequestContext);
                });
            }
            //如果command不支持fallback操作
            else {
                /* fallback is disabled so throw HystrixRuntimeException */
                Exception e = originalException;
                logger.debug("Fallback disabled for HystrixCommand so will throw HystrixRuntimeException. ", e); // debug only since we're throwing the exception and someone higher will do something with it

                /* executionHook for all errors */
                e = wrapWithOnErrorHook(failureType, e);
                if (e instanceof HystrixBadRequestException) {
                    /*
                     * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                     */
                    eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                    executionResult = executionResult.addEvent(HystrixEventType.BAD_REQUEST);
                    fallbackLogicApplied = Observable.error(e);
                } else {
                    fallbackLogicApplied = Observable.<R>error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and fallback disabled.", e, null));
                }
            }
        }

        return fallbackLogicApplied.doOnTerminate(() -> {
            // record that we're completed (to handle non-successful events we do it here as well as at the end of executeCommand
            isExecutionComplete.set(true);
        }).doOnEach(n -> {
            setRequestContextIfNeeded(currentRequestContext);
        });
    }

    /**
     * Returns true iff the t was caused by a java.lang.Error that is unrecoverable.  Note: not all java.lang.Errors are unrecoverable.
     * @see <a href="https://github.com/Netflix/Hystrix/issues/713"></a> for more context
     * Solution taken from <a href="https://github.com/ReactiveX/RxJava/issues/748"></a>
     *
     * The specific set of Error that are considered unrecoverable are:
     * <ul>
     * <li>{@code StackOverflowError}</li>
     * <li>{@code VirtualMachineError}</li>
     * <li>{@code ThreadDeath}</li>
     * <li>{@code LinkageError}</li>
     * </ul>
     *
     * @param t throwable to check
     * @return true iff the t was caused by a java.lang.Error that is unrecoverable
     */
    private boolean isUnrecoverable(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof StackOverflowError) {
                return true;
            } else if (cause instanceof VirtualMachineError) {
                return true;
            } else if (cause instanceof ThreadDeath) {
                return true;
            } else if (cause instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecoverableError(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof java.lang.Error) {
                return !isUnrecoverable(t);
            }
        }
        return false;
    }

    protected void handleThreadEnd() {
        if (endCurrentThreadExecutingCommand.get() != null) {
            endCurrentThreadExecutingCommand.get().call();
        }
        if (executionResult.isExecutedInThread()) {
            HystrixCounters.decrementGlobalConcurrentThreads();
            threadPool.markThreadCompletion();
            try {
                executionHook.onThreadComplete(this);
            } catch (Throwable hookEx) {
                logger.warn("Error calling HystrixCommandExecutionHook.onThreadComplete", hookEx);
            }
        }
    }

    /**
     *
     * @return if onNext events should be reported on
     * This affects {@link HystrixRequestLog}, and {@link HystrixEventNotifier} currently.
     * Metrics will be affected once they are in place
     */
    protected boolean shouldOutputOnNextEvents() {
        return false;
    }

    //Hystrix Command超时处理器
    private static class HystrixObservableTimeoutOperator<R> implements Operator<R, R> {
        final AbstractCommand<R> originalCommand;
        public HystrixObservableTimeoutOperator(final AbstractCommand<R> originalCommand) {
            this.originalCommand = originalCommand;
        }
        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> child) {
            final CompositeSubscription s = new CompositeSubscription();
            // if the child unsubscribes we unsubscribe our parent as well
            child.add(s);
            //超时处理任务
            final HystrixContextRunnable timeoutRunnable =
                    new HystrixContextRunnable(originalCommand.concurrencyStrategy, () -> child.onError(new HystrixTimeoutException()));
            //创建并设置TimerListener
            TimerListener listener = new TimerListener() {
                @Override
                public void tick() {
                    if (originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)) {
                        originalCommand.eventNotifier.markEvent(HystrixEventType.TIMEOUT, originalCommand.commandKey);
                        s.unsubscribe();//如果command已经执行超时，取消command的执行线程
                        timeoutRunnable.run();//运行timeoutRunnable，发送onError事件（HystrixTimeoutException）
                    }
                }
                @Override
                public int getIntervalTimeInMilliseconds() {
                    return originalCommand.properties.executionTimeoutInMilliseconds().get();
                }
            };
            //将listener添加到HystrixTimer
            final Reference<TimerListener> tl = HystrixTimer.getInstance().addTimerListener(listener);
            // set externally so execute/queue can see this
            originalCommand.timeoutTimer.set(tl);

            /**
             * If this subscriber receives values it means the parent succeeded/completed
             */
            Subscriber<R> parent = new Subscriber<R>() {
                @Override
                public void onCompleted() {
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        tl.clear();
                        child.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable e) {
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        tl.clear();
                        child.onError(e);
                    }
                }

                @Override
                public void onNext(R v) {
                    if (isNotTimedOut()) {
                        child.onNext(v);
                    }
                }

                private boolean isNotTimedOut() {
                    // if already marked COMPLETED (by onNext) or succeeds in setting to COMPLETED
                    return originalCommand.isCommandTimedOut.get() == TimedOutStatus.COMPLETED ||
                            originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.COMPLETED);
                }

            };

            // if s is unsubscribed we want to unsubscribe the parent
            s.add(parent);

            return parent;
        }

    }

    private static void setRequestContextIfNeeded(final HystrixRequestContext currentRequestContext) {
        if (!HystrixRequestContext.isCurrentThreadInitialized()) {
            // even if the user Observable doesn't have context we want it set for chained operators
            HystrixRequestContext.setContextOnCurrentThread(currentRequestContext);
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use if a fallback occurs.
     * 
     * @return TryableSemaphore
     */
    protected TryableSemaphore getFallbackSemaphore() {
        if (fallbackSemaphoreOverride == null) {
            TryableSemaphore _s = fallbackSemaphorePerCircuit.get(commandKey.name());
            if (_s == null) {
                // we didn't find one cache so setup
                fallbackSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.fallbackIsolationSemaphoreMaxConcurrentRequests()));
                // assign whatever got set (this or another thread)
                return fallbackSemaphorePerCircuit.get(commandKey.name());
            } else {
                return _s;
            }
        } else {
            return fallbackSemaphoreOverride;
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use for execution if not running in a separate thread.
     * 
     * @return TryableSemaphore
     */
    protected TryableSemaphore getExecutionSemaphore() {
        if (properties.executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.SEMAPHORE)) {
            if (executionSemaphoreOverride == null) {
                TryableSemaphore _s = executionSemaphorePerCircuit.get(commandKey.name());
                if (_s == null) {
                    // we didn't find one cache so setup
                    executionSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.executionIsolationSemaphoreMaxConcurrentRequests()));
                    // assign whatever got set (this or another thread)
                    return executionSemaphorePerCircuit.get(commandKey.name());
                } else {
                    return _s;
                }
            } else {
                return executionSemaphoreOverride;
            }
        } else {
            // return NoOp implementation since we're not using SEMAPHORE isolation
            return TryableSemaphoreNoOp.DEFAULT;
        }
    }

    /**
     * Each concrete implementation of AbstractCommand should return the name of the fallback method as a String
     * This will be used to determine if the fallback "exists" for firing the onFallbackStart/onFallbackError hooks
     * @return method name of fallback
     */
    protected abstract String getFallbackMethodName();

    /**
     * For the given command instance, does it define an actual fallback method?
     * @param cmd command instance
     * @return true iff there is a user-supplied fallback method on the given command instance
     */
    /*package-private*/
    //判断是否实现了fallBack方法
    static boolean isFallbackUserSupplied(final AbstractCommand<?> cmd) {
        HystrixCommandKey commandKey = cmd.commandKey;
        Boolean containsFromMap = commandContainsFallback.get(commandKey);
        if (containsFromMap != null) {
            return containsFromMap;
        } else {
            Boolean toInsertIntoMap;
            try {
                cmd.getClass().getDeclaredMethod(cmd.getFallbackMethodName());
                toInsertIntoMap = true;
            } catch (NoSuchMethodException nsme) {
                toInsertIntoMap = false;
            }
            commandContainsFallback.put(commandKey, toInsertIntoMap);
            return toInsertIntoMap;
        }
    }

    protected static class ObservableCommand<R> extends Observable<R> {
        private final AbstractCommand<R> command;

        ObservableCommand(OnSubscribe<R> func, final AbstractCommand<R> command) {
            super(func);
            this.command = command;
        }

        public AbstractCommand<R> getCommand() {
            return command;
        }

        ObservableCommand(final Observable<R> originalObservable, final AbstractCommand<R> command) {
            super(originalObservable::unsafeSubscribe);
            this.command = command;
        }

    }

    /**
     * Wraps a source Observable and remembers the original HystrixCommand.
     * <p>
     * Used for request caching so multiple commands can respond from a single Observable but also get access to the originating HystrixCommand.
     * 
     * @param <R>
     */
    protected static class CachedObservableOriginal<R> extends ObservableCommand<R> {

        final AbstractCommand<R> originalCommand;

        CachedObservableOriginal(final Observable<R> actual, AbstractCommand<R> command) {
            super(actual::unsafeSubscribe, command);
            this.originalCommand = command;
        }
    }

    /**
     * Wraps a CachedObservableOriginal as it is being returned from cache.
     * <p>
     * As the Observable completes it copies state used for ExecutionResults
     * and metrics that differentiate between the original and the de-duped "response from cache" command execution.
     * 
     * @param <R>
     */
    protected static class CachedObservableResponse<R> extends ObservableCommand<R> {
        final CachedObservableOriginal<R> originalObservable;

        CachedObservableResponse(final CachedObservableOriginal<R> originalObservable, final AbstractCommand<R> commandOfDuplicateCall) {
            super(observer -> {
                originalObservable.subscribe(new Subscriber<R>() {

                    @Override
                    public void onCompleted() {
                        completeCommand();
                        observer.onCompleted();
                    }

                    @Override
                    public void onError(Throwable e) {
                        completeCommand();
                        observer.onError(e);
                    }

                    @Override
                    public void onNext(R v) {
                        observer.onNext(v);
                    }

                    private void completeCommand() {
                        // when the observable completes we then update the execution results of the duplicate command
                        // set this instance to the result that is from cache
                        commandOfDuplicateCall.executionResult = originalObservable.originalCommand.executionResult;
                        // add that this came from cache
                        commandOfDuplicateCall.executionResult = commandOfDuplicateCall.executionResult.addEvent(HystrixEventType.RESPONSE_FROM_CACHE);
                        // set the execution time to 0 since we retrieved from cache
                        commandOfDuplicateCall.executionResult = commandOfDuplicateCall.executionResult.setExecutionLatency(-1);
                        // record that this command executed
                        commandOfDuplicateCall.recordExecutedCommand();
                    }
                });
            }, commandOfDuplicateCall);
            this.originalObservable = originalObservable;
        }

        /*
         * This is a cached response so we want the command of the observable we're wrapping.
         */
        public AbstractCommand<R> getCommand() {
            return originalObservable.originalCommand;
        }
    }

    /**
     * @return {@link HystrixCommandGroupKey} used to group together multiple {@link AbstractCommand} objects.
     *         <p>
     *         The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace with,
     *         common business purpose etc.
     */
    public HystrixCommandGroupKey getCommandGroup() {
        return commandGroup;
    }

    /**
     * @return {@link HystrixCommandKey} identifying this command instance for statistics, circuit-breaker, properties, etc.
     */
    public HystrixCommandKey getCommandKey() {
        return commandKey;
    }

    /**
     * @return {@link HystrixThreadPoolKey} identifying which thread-pool this command uses (when configured to run on separate threads via
     *         {@link HystrixCommandProperties#executionIsolationStrategy()}).
     */
    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /* package */HystrixCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * The {@link HystrixCommandMetrics} associated with this {@link AbstractCommand} instance.
     *
     * @return HystrixCommandMetrics
     */
    public HystrixCommandMetrics getMetrics() {
        return metrics;
    }

    /**
     * The {@link HystrixCommandProperties} associated with this {@link AbstractCommand} instance.
     * 
     * @return HystrixCommandProperties
     */
    public HystrixCommandProperties getProperties() {
        return properties;
    }

    /**
     * Record that this command was executed in the HystrixRequestLog.
     * <p>
     * This can be treated as an async operation as it just adds a references to "this" in the log even if the current command is still executing.
     */
    protected void recordExecutedCommand() {
        if (properties.requestLogEnabled().get()) {
            // log this command execution regardless of what happened
            if (currentRequestLog != null) {
                currentRequestLog.addExecutedCommand(this);
            }
        }
    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* Operators that implement hook application */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    private class CommandHookApplication implements Operator<R, R> {
        private final HystrixInvokableInfo<R> cmd;

        CommandHookApplication(HystrixInvokableInfo<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        executionHook.onSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    //can't add the calls to executionHook.onError here, since this requires a FailureType param as well
                    subscriber.onError(e);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    private class ExecutionHookApplication implements Operator<R, R> {
        private final HystrixInvokableInfo<R> cmd;

        ExecutionHookApplication(HystrixInvokableInfo<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        executionHook.onExecutionSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onExecutionSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    Exception wrappedEx = wrapWithOnExecutionErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnExecutionEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    private class FallbackHookApplication implements Operator<R, R> {
        private final HystrixInvokableInfo<R> cmd;

        FallbackHookApplication(HystrixInvokableInfo<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        executionHook.onFallbackSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onFallbackSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    Exception wrappedEx = wrapWithOnFallbackErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnFallbackEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    private Exception wrapWithOnExecutionErrorHook(Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            return executionHook.onExecutionError(this, e);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionError", hookEx);
            return e;
        }
    }

    private Exception wrapWithOnFallbackErrorHook(Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            if (isFallbackUserSupplied(this)) {
                return executionHook.onFallbackError(this, e);
            } else {
                return e;
            }
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackError", hookEx);
            return e;
        }
    }

    private Exception wrapWithOnErrorHook(FailureType failureType, Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            return executionHook.onError(this, failureType, e);
        } catch (HystrixBadRequestException badRequestException) {
            return badRequestException;
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
            return e;
        }
    }

    private R wrapWithOnExecutionEmitHook(R r) {
        try {
            return executionHook.onExecutionEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionEmit", hookEx);
            return r;
        }
    }

    private R wrapWithOnFallbackEmitHook(R r) {
        try {
            return executionHook.onFallbackEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackEmit", hookEx);
            return r;
        }
    }

    private R wrapWithOnEmitHook(R r) {
        try {
            return executionHook.onEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
            return r;
        }
    }


    /**
     * Take an Exception and determine whether to throw it, its cause or a new HystrixRuntimeException.
     * <p>
     * This will only throw an HystrixRuntimeException, HystrixBadRequestException or IllegalStateException
     * 
     * @param e initial exception
     * @return HystrixRuntimeException, HystrixBadRequestException or IllegalStateException
     */
    protected RuntimeException decomposeException(Exception e) {
        if (e instanceof IllegalStateException) {
            return (IllegalStateException) e;
        }
        if (e instanceof HystrixBadRequestException) {
            return (HystrixBadRequestException) e;
        }
        if (e.getCause() instanceof HystrixBadRequestException) {
            return (HystrixBadRequestException) e.getCause();
        }
        if (e instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e;
        }
        // if we have an exception we know about we'll throw it directly without the wrapper exception
        if (e.getCause() instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e.getCause();
        }
        // we don't know what kind of exception this is so create a generic message and throw a new HystrixRuntimeException
        String message = getLogMessagePrefix() + " failed while executing.";
        logger.debug(message, e); // debug only since we're throwing the exception and someone higher will do something with it
        return new HystrixRuntimeException(FailureType.COMMAND_EXCEPTION, this.getClass(), message, e, null);

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* TryableSemaphore */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Semaphore that only supports tryAcquire and never blocks and that supports a dynamic permit count.
     * <p>
     * Using AtomicInteger increment/decrement instead of java.util.concurrent.Semaphore since we don't need blocking and need a custom implementation to get the dynamic permit count and since
     * AtomicInteger achieves the same behavior and performance without the more complex implementation of the actual Semaphore class using AbstractQueueSynchronizer.
     */
    /* package */
    static class TryableSemaphoreActual implements TryableSemaphore {
        protected final HystrixProperty<Integer> numberOfPermits;
        private final AtomicInteger count = new AtomicInteger(0);

        public TryableSemaphoreActual(HystrixProperty<Integer> numberOfPermits) {
            this.numberOfPermits = numberOfPermits;
        }

        @Override
        public boolean tryAcquire() {
            int currentCount = count.incrementAndGet();
            if (currentCount > numberOfPermits.get()) {
                count.decrementAndGet();
                return false;
            } else {
                return true;
            }
        }

        @Override
        public void release() {
            count.decrementAndGet();
        }

        @Override
        public int getNumberOfPermitsUsed() {
            return count.get();
        }

    }

    /* package */
    static class TryableSemaphoreNoOp implements TryableSemaphore {

        public static final TryableSemaphore DEFAULT = new TryableSemaphoreNoOp();

        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {

        }

        @Override
        public int getNumberOfPermitsUsed() {
            return 0;
        }

    }

    /* package */static
    interface TryableSemaphore {

        /**
         * Use like this:
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         * 
         * @return boolean
         */
        public abstract boolean tryAcquire();

        /**
         * ONLY call release if tryAcquire returned true.
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         */
        public abstract void release();

        public abstract int getNumberOfPermitsUsed();

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* Result Status */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* RequestCache */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Key to be used for request caching.
     * <p>
     * By default this returns null which means "do not cache".
     * <p>
     * To enable caching override this method and return a string key uniquely representing the state of a command instance.
     * <p>
     * If multiple command instances in the same request scope match keys then only the first will be executed and all others returned from cache.
     * 
     * @return cacheKey
     */
    //返回请求的缓存的KEY，默认返回null意味着不支持cache。如果一个key对应多个command，会返回检索到的第一个command的值
    //如果要使用cache，需要重写该方法
    protected String getCacheKey() {
        return null;
    }

    public String getPublicCacheKey() {
        return getCacheKey();
    }

    //判断是否支持cache
    protected boolean isRequestCachingEnabled() {
        return properties.requestCacheEnabled().get() && getCacheKey() != null;
    }

    protected String getLogMessagePrefix() {
        return getCommandKey().name();
    }

    /**
     * Whether the 'circuit-breaker' is open meaning that <code>execute()</code> will immediately return
     * the <code>getFallback()</code> response and not attempt a HystrixCommand execution.
     *
     * 4 columns are ForcedOpen | ForcedClosed | CircuitBreaker open due to health ||| Expected Result
     *
     * T | T | T ||| OPEN (true)
     * T | T | F ||| OPEN (true)
     * T | F | T ||| OPEN (true)
     * T | F | F ||| OPEN (true)
     * F | T | T ||| CLOSED (false)
     * F | T | F ||| CLOSED (false)
     * F | F | T ||| OPEN (true)
     * F | F | F ||| CLOSED (false)
     *
     * @return boolean
     */
    public boolean isCircuitBreakerOpen() {
        return properties.circuitBreakerForceOpen().get() || (!properties.circuitBreakerForceClosed().get() && circuitBreaker.isOpen());
    }

    /**
     * If this command has completed execution either successfully, via fallback or failure.
     * 
     * @return boolean
     */
    public boolean isExecutionComplete() {
        return isExecutionComplete.get();
    }

    /**
     * Whether the execution occurred in a separate thread.
     * <p>
     * This should be called only once execute()/queue()/fireOrForget() are called otherwise it will always return false.
     * <p>
     * This specifies if a thread execution actually occurred, not just if it is configured to be executed in a thread.
     * 
     * @return boolean
     */
    public boolean isExecutedInThread() {
        return executionResult.isExecutedInThread();
    }

    /**
     * Whether the response was returned successfully either by executing <code>run()</code> or from cache.
     * 
     * @return boolean
     */
    public boolean isSuccessfulExecution() {
        return executionResult.getEventCounts().contains(HystrixEventType.SUCCESS);
    }

    /**
     * Whether the <code>run()</code> resulted in a failure (exception).
     * 
     * @return boolean
     */
    public boolean isFailedExecution() {
        return executionResult.getEventCounts().contains(HystrixEventType.FAILURE);
    }

    /**
     * Get the Throwable/Exception thrown that caused the failure.
     * <p>
     * If <code>isFailedExecution() == true</code> then this would represent the Exception thrown by the <code>run()</code> method.
     * <p>
     * If <code>isFailedExecution() == false</code> then this would return null.
     * 
     * @return Throwable or null
     */
    public Throwable getFailedExecutionException() {
        return executionResult.getException();
    }

    /**
     * Get the Throwable/Exception emitted by this command instance prior to checking the fallback.
     * This exception instance may have been generated via a number of mechanisms:
     * 1) failed execution (in this case, same result as {@link #getFailedExecutionException()}.
     * 2) timeout
     * 3) short-circuit
     * 4) rejection
     * 5) bad request
     *
     * If the command execution was successful, then this exception instance is null (there was no exception)
     *
     * Note that the caller of the command may not receive this exception, as fallbacks may be served as a response to
     * the exception.
     *
     * @return Throwable or null
     */
    public Throwable getExecutionException() {
        return executionResult.getExecutionException();
    }

    /**
     * Whether the response received from was the result of some type of failure
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseFromFallback() {
        return executionResult.getEventCounts().contains(HystrixEventType.FALLBACK_SUCCESS);
    }

    /**
     * Whether the response received was the result of a timeout
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseTimedOut() {
        return executionResult.getEventCounts().contains(HystrixEventType.TIMEOUT);
    }

    /**
     * Whether the response received was a fallback as result of being
     * short-circuited (meaning <code>isCircuitBreakerOpen() == true</code>) and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseShortCircuited() {
        return executionResult.getEventCounts().contains(HystrixEventType.SHORT_CIRCUITED);
    }

    /**
     * Whether the response is from cache and <code>run()</code> was not invoked.
     * 
     * @return boolean
     */
    public boolean isResponseFromCache() {
        return executionResult.getEventCounts().contains(HystrixEventType.RESPONSE_FROM_CACHE);
    }

    /**
     * Whether the response received was a fallback as result of being rejected via sempahore
     *
     * @return boolean
     */
    public boolean isResponseSemaphoreRejected() {
        return executionResult.isResponseSemaphoreRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected via threadpool
     *
     * @return boolean
     */
    public boolean isResponseThreadPoolRejected() {
        return executionResult.isResponseThreadPoolRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected (either via threadpool or semaphore)
     *
     * @return boolean
     */
    public boolean isResponseRejected() {
        return executionResult.isResponseRejected();
    }

    /**
     * List of HystrixCommandEventType enums representing events that occurred during execution.
     * <p>
     * Examples of events are SUCCESS, FAILURE, TIMEOUT, and SHORT_CIRCUITED
     * 
     * @return {@code List<HystrixEventType>}
     */
    public List<HystrixEventType> getExecutionEvents() {
        return executionResult.getOrderedList();
    }

    /**
     * Number of emissions of the execution of a command.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming command
     */
    @Override
    public int getNumberEmissions() {
        return executionResult.getEventCounts().getCount(HystrixEventType.EMIT);
    }

    /**
     * Number of emissions of the execution of a fallback.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming fallback
     */
    @Override
    public int getNumberFallbackEmissions() {
        return executionResult.getEventCounts().getCount(HystrixEventType.FALLBACK_EMIT);
    }

    @Override
    public int getNumberCollapsed() {
        return executionResult.getEventCounts().getCount(HystrixEventType.COLLAPSED);
    }

    @Override
    public HystrixCollapserKey getOriginatingCollapserKey() {
        return executionResult.getCollapserKey();
    }

    /**
     * The execution time of this command instance in milliseconds, or -1 if not executed.
     * 
     * @return int
     */
    public int getExecutionTimeInMilliseconds() {
        return executionResult.getExecutionLatency();
    }

    /**
     * Time in Nanos when this command instance's run method was called, or -1 if not executed 
     * for e.g., command threw an exception
      *
      * @return long
     */
    public long getCommandRunStartTimeInNanos() {
        return executionResult.getCommandRunStartTimeInNanos();
    }

    @Override
    public ExecutionResult.EventCounts getEventCounts() {
        return executionResult.getEventCounts();
    }

    protected Exception getExceptionFromThrowable(Throwable t) {
        Exception e;
        if (t instanceof Exception) {
            e = (Exception) t;
        } else {
            // Hystrix 1.x uses Exception, not Throwable so to prevent a breaking change Throwable will be wrapped in Exception
            e = new Exception("Throwable caught while executing.", t);
        }
        return e;
    }
}

package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixObservableCommand;
import org.junit.Test;
import rx.Observable;
import rx.Observer;
import rx.Subscriber;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2019/11/28
 */
public class ObservableCommandHello extends HystrixObservableCommand<String> {
    private String name;

    public ObservableCommandHello() {
        super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        this.name = "are you ok?";
    }

    @Override
    protected Observable<String> construct() {
        System.out.println("[construct] thread: " + Thread.currentThread().getName());
        return Observable.create(new Observable.OnSubscribe<String>() {
            @Override
            public void call(Subscriber<? super String> observer) {
                System.out.println("[construct-call] thread: " + Thread.currentThread().getName());
                if (!observer.isUnsubscribed()) {
                    observer.onNext("Hello1" + " thread: " + Thread.currentThread().getName());
                    observer.onNext("Hello2" + " thread: " + Thread.currentThread().getName());
                    observer.onNext(name + " thread:" + Thread.currentThread().getName());
                    System.out.println("complete before-----" + "thread: " + Thread.currentThread().getName());
                    observer.onCompleted();
                    System.out.println("complete after------" + "thread: " + Thread.currentThread().getName());
                    observer.onCompleted(); // 不会执行到
                    observer.onNext("abc"); // 不会执行到
                }
            }
        });
    }

    @Test
    // 反应执行
    public void testObservableCommand() throws Exception {
        HystrixObservableCommand<String> observableCommand = new ObservableCommandHello();
        observableCommand.toObservable().subscribe(new Observer<String>() {
            @Override
            public void onCompleted() {
                System.out.println("complate");
            }

            @Override
            public void onError(Throwable throwable) {
                System.out.println("error");
            }

            @Override
            public void onNext(String s) {
                System.err.println("Thread:"+Thread.currentThread().getName());
                System.out.println("hnext:" + s);
            }
        });

//        HystrixObservableCommand<String> observableCommand1 = new ObservableCommandHello();
//        String result = observableCommand1.observe().toBlocking().first();
//        System.out.println(result);
//
    }
}

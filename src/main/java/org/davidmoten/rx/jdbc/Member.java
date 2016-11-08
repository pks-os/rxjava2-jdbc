package org.davidmoten.rx.jdbc;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.MaybeSource;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Predicate;
import io.reactivex.subjects.PublishSubject;

public final class Member<T> {

    private static final int NOT_INITIALIZED_NOT_IN_USE = 0;
    private static final int INITIALIZED_IN_USE = 1;
    private static final int INITIALIZED_NOT_IN_USE = 2;
    private final AtomicInteger state = new AtomicInteger(NOT_INITIALIZED_NOT_IN_USE);

    private volatile T value;
    private final PublishSubject<Member<T>> subject;
    private final Callable<T> factory;
    private final long retryDelayMs;
    private final Predicate<T> healthy;
    private final Consumer<T> disposer;

    public Member(PublishSubject<Member<T>> subject, Callable<T> factory, Predicate<T> healthy, Consumer<T> disposer,
            long retryDelayMs) {
        this.subject = subject;
        this.factory = factory;
        this.healthy = healthy;
        this.disposer = disposer;
        this.retryDelayMs = retryDelayMs;
    }

    public Maybe<Member<T>> checkout() {
        return Maybe.defer(() -> {
            if (state.compareAndSet(NOT_INITIALIZED_NOT_IN_USE, INITIALIZED_IN_USE)) {
                // errors thrown by factory.call() handled in retryWhen
                value = factory.call();
                return Maybe.just(Member.this);
            } else if (state.compareAndSet(INITIALIZED_NOT_IN_USE, INITIALIZED_IN_USE)) {
                try {
                    if (healthy.test(value)) {
                        return Maybe.just(Member.this);
                    } else {
                        return dispose();
                    }
                } catch (Throwable e) {
                    return dispose();
                }
            } else {
                return Maybe.empty();
            }
        }).retryWhen(errors -> errors.flatMap(e -> Flowable.timer(retryDelayMs, TimeUnit.SECONDS)));
    }

    private MaybeSource<? extends Member<T>> dispose() {
        try {
            disposer.accept(value);
        } catch (Throwable t) {
            // ignore
        }
        state.set(NOT_INITIALIZED_NOT_IN_USE);
        return Maybe.empty();
    }

    public void checkin() {
        state.set(INITIALIZED_NOT_IN_USE);
        subject.onNext(this);
    }

    public T value() {
        return value;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Member [value=");
        builder.append(value);
        builder.append(", state=");
        builder.append(state.get());
        builder.append("]");
        return builder.toString();
    }

}
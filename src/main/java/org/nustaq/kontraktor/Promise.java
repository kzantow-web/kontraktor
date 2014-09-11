package org.nustaq.kontraktor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by ruedi on 20.05.14.
 */
public class Promise<T> implements Future<T> {

    // fixme: volatile ? (assumption: lock triggers cache coherence. unsure, no errors until now)
    protected Object result;
    protected Object error;
    protected Callback resultReceiver;
    protected boolean hadResult;
    protected boolean hasFired;
    // not necessary in many cases and also increases cost
    // of allocation. However for now stay safe and optimize
    // from a proven-working implementation
    // note: if removed some field must set to volatile
    final AtomicBoolean lock = new AtomicBoolean(false); // (AtomicFieldUpdater is slower!)
    String id;
    Future nextFuture;

    public Promise(T result, Object error) {
        this.result = result;
        this.error = error;
        hadResult = true;
    }

    public Promise(T result) {
        this(result,null);
    }

    public Promise() {}

    public String getId() {
        return id;
    }

    public Promise<T> setId(String id) {
        this.id = id;
        return this;
    }

    @Override
    public <OUT> Future<OUT> map(final Filter<T, OUT> filter) {
        final Promise<OUT> promise = new Promise<>();
        then(new Callback<T>() {
            @Override
            public void receive(T result, Object error) {
                filter.map(result, error).then(promise);
            }
        });
        return promise;
    }

    @Override
    public Future then(Callback resultCB) {
        // FIXME: this can be implemented more efficient
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (resultReceiver != null)
                throw new RuntimeException("Double register of future listener");
            resultReceiver = resultCB;
            if (hadResult) {
                hasFired = true;
                if (nextFuture == null) {
                    nextFuture = new Promise(result, error);
                    lock.set(false);
                    resultCB.receive(result, error);
                } else {
                    lock.set(false);
                    resultCB.receive(result, error);
                    nextFuture.receive(result, error);
                    return nextFuture;
                }
            }
            if (resultCB instanceof Future) {
                return (Future) resultCB;
            }
            lock.set(false);
            while( !lock.compareAndSet(false,true) ) {}
            if (nextFuture == null) {
                return nextFuture = new Promise();
            } else {
                return nextFuture;
            }
        } finally {
            lock.set(false);
        }
    }

    /**
     * special method for tricky things. Creates a nextFuture or returns it. Not threadsafe !
     * current
     * @return
     */
    public Promise getNext() {
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (nextFuture == null)
                return new Promise();
            else
                return (Promise) nextFuture;
        } finally {
            lock.set(false);
        }
    }

    /**
     * same as then, but avoid creation of new future
     * @param resultCB
     */
    public void finishWith(Callback resultCB) {
        // FIXME: this can be implemented more efficient
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (resultReceiver != null)
                throw new RuntimeException("Double register of future listener");
            resultReceiver = resultCB;
            if (hadResult) {
                hasFired = true;
                lock.set(false);
                resultCB.receive(result, error);
            }
        } finally {
            lock.set(false);
        }
    }

    @Override
    public final void receive(Object res, Object error) {
        // ensure correct thread in case actor cascades futures
//        if ( Thread.currentThread() != currentThread && currentThread instanceof DispatcherThread) {
//            new CallbackWrapper((DispatcherThread) currentThread, this).receive(res, error);
//        }
//        else
        {
            this.result = res;
            this.error = error;
            while( !lock.compareAndSet(false,true) ) {}
            try {
                if (hadResult) {
                    lock.set(false);
                    throw new RuntimeException("Double result received on future");
                }
                hadResult = true;
                if (resultReceiver != null) {
                    if (hasFired) {
                        lock.set(false);
                        throw new RuntimeException("Double fire on callback");
                    }
                    hasFired = true;
                    lock.set(false);
                    resultReceiver.receive(result, error);
                    resultReceiver = null;
                    while (!lock.compareAndSet(false, true)) {
                    }
                    if (nextFuture != null) {
                        lock.set(false);
                        nextFuture.receive(result, error);
                    }
                    return;
                }
            } finally {
                lock.set(false);
            }
        }
    }

    public T getResult() {
        return (T) result;
    }

    @Override
    public void signal() {
        receive(null, null);
    }

    public Object getError() {
        return error;
    }

    // debug
    public boolean _isHadResult() {
        return hadResult;
    }

    // debug
    public boolean _isHasFired() {
        return hasFired;
    }

    @Override
    public String toString() {
        return "Result{" +
            "result=" + result +
            ", error=" + error +
            '}';
    }
}

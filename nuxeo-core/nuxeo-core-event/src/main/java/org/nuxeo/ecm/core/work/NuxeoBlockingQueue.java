/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.work;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.nuxeo.ecm.core.work.api.WorkQueueMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An abstract {@link BlockingQueue} suitable for a fixed-sized {@link java.util.concurrent.ThreadPoolExecutor
 * ThreadPoolExecutor}, that can be implemented in terms of a few methods. {@link #offer} always succeeds.
 * <p />
 * What we want:
 * <ul>
 *     <li>a pool keeping an active status - used for hotreload and stop</li>
 *     <li>a pool is called on take() (ThreadPoolExecutor settings), it should always return something except during
 *     shutdown. During shutdown we return null to stop the current worker of executor
 *     {@link ThreadPoolExecutor#getTask}</li>
 *     <li>a pool do not returning his works during shutdowNow, in case of redis we don't want to get all works onto
 *     stopping Nuxeo instance, keeping them in redis is nice. In case of in memory, we lost works when server shutdown
 *     or during hot reload</li>
 * </ul>
 *
 * @since 5.8
 */
public abstract class NuxeoBlockingQueue<Q extends WorkQueuing> extends AbstractQueue<Runnable>
        implements BlockingQueue<Runnable> {

    // need to use sl4j here as we want to log at debug level, we need an efficient way to print message with parameters
    private static final Logger logger = LoggerFactory.getLogger(NuxeoBlockingQueue.class);

    /*
     * ThreadPoolExecutor uses a BlockingQueue but the Java 7 implementation only calls these methods on it:
     * - isEmpty()
     * - size()
     * - poll(timeout, unit): not used, as core pool size = max size and no core thread timeout
     * - take()
     * - offer(e)
     * - remove(e)
     * - toArray(), toArray(a): for purge and shutdown
     * - drainTo(c)
     * - iterator() : hasNext(), next(), remove() (called by toArray)
     */

    protected final ReentrantLock activationLock = new ReentrantLock();

    protected final Condition activation = activationLock.newCondition();

    protected volatile boolean active = false;

    protected final String queueId;

    protected final Q queuing;

    protected NuxeoBlockingQueue(String queueId, Q queuing) {
        this.queueId = queueId;
        this.queuing = queuing;
    }

    @Override
    public final boolean offer(Runnable r) {
        try {
            put(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt status
            throw new RuntimeException(
                    String.format("Queue was interrupted NuxeoBlockingQueue(queueId=%s, active=%s)", queueId, active),
                    e);
        }
        return true;
    }

    @Override
    public final void put(Runnable r) throws InterruptedException {
        putElement(r);
    }

    @Override
    public final boolean offer(Runnable r, long timeout, TimeUnit unit) throws InterruptedException {
        // not needed for ThreadPoolExecutor
        put(r);
        return true;
    }

    @Override
    public final Runnable poll() {
        logger.info("Before NuxeoBlockingQueue(queueId={}, active={})#poll()", queueId, active);
        try {
            if (!active) {
                return null;
            }
            Runnable runnable = pollElement();
            if (runnable == null) {
                return null;
            }
            if (!active) {
                queuing.workReschedule(queueId, WorkHolder.getWork(runnable));
                return null;
            }
            return runnable;
        } finally {
            logger.info("After NuxeoBlockingQueue(queueId={}, active={})#poll()", queueId, active);
        }
    }

    @Override
    public final Runnable peek() {
        // not needed for ThreadPoolExecutor
        throw new UnsupportedOperationException("not supported");
    }

    /**
     * @since 9.3
     */
    @Override
    public final Runnable take() throws InterruptedException {
        logger.info("Before NuxeoBlockingQueue(queueId={}, active={})#take()", queueId, active);
        try {
            // as we use ThreadPoolExecutor not timedOut (see ThreadPoolExecutor#getTasks) return null when queue is not
            // activated (in fact deactivated) to stop worker in case of shutdown
            // for an unknown reason, this will cause an infinite loop if queue is deactivated (whereas we've just
            // waited for activation) and executor is not shutdown
            // this shouldn't happen because we deactivate queue before stopping executor
            awaitActivation();
            if (!active) {
                return null;
            }
            Runnable runnable = takeElement();
            if (!active) {
                // reschedule the work if queue was deactivated during take
                queuing.workReschedule(queueId, WorkHolder.getWork(runnable));
                return null;
            }
            return runnable;
        } finally {
            logger.info("After NuxeoBlockingQueue(queueId={}, active={})#take()", queueId, active);
        }
    }

    /**
     * @since 9.3
     */
    @Override
    public final Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
        // currently not used by our ThreadPoolExecutor, but implement it as the others just in case
        logger.info("Before NuxeoBlockingQueue(queueId={}, active={})#poll(timeout={}, unit={})", queueId, active,
                timeout, unit);
        try {
            long nanos = unit.toNanos(timeout);
            nanos = awaitActivation(nanos);
            if (nanos <= 0 || !active) {
                // waiting time elapsed - queue still not active
                return null;
            }
            Runnable runnable = pollElement(nanos, TimeUnit.NANOSECONDS);
            if (runnable == null) {
                return null;
            }
            if (!active) {
                // reschedule the work if queue was deactivated during take
                queuing.workReschedule(queueId, WorkHolder.getWork(runnable));
                return null;
            }
            return null;
        } finally {
            logger.info("After NuxeoBlockingQueue(queueId={}, active={})#poll(timeout={}, unit={})", queueId, active,
                    timeout, unit);
        }
    }

    @Override
    public boolean contains(Object o) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException();
    }

    /**
     * Overrides it in order to return true if the queue is inactive.
     *
     * @since 9.3
     */
    @Override
    public boolean isEmpty() {
        return !active || super.isEmpty();
    }

    @Override
    public int size() {
        return getQueueSize();
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Iterator<Runnable> iterator() {
        return new Iter();
    }

    /*
     * Used by drainQueue/purge methods of ThreadPoolExector through toArray.
     */
    private class Iter implements Iterator<Runnable> {

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Runnable next() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public int drainTo(Collection<? super Runnable> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super Runnable> c, int maxElements) {
        for (int i = 0; i < maxElements; i++) {
            Runnable r = poll();
            if (r == null) {
                return i;
            }
            c.add(r);
        }
        return maxElements;
    }

    /**
     * Gets the size of the queue.
     */
    public abstract int getQueueSize();

    /**
     * Adds an element into this queue, waiting if necessary for space to become available.
     */
    protected abstract void putElement(Runnable r) throws InterruptedException;

    /**
     * Retrieves and removes an element from the queue, or returns null if the queue is empty.
     */
    protected abstract Runnable pollElement();

    /**
     * Provides a default implementation which just calls {@link #pollElement(long, TimeUnit)} infinitely with a timeout
     * of one day.
     * <p />
     * Queues supporting this taking element mechanism needs to override this method.
     *
     * @see BlockingQueue#take()
     * @since 9.3
     */
    protected Runnable takeElement() throws InterruptedException {
        for (;;) {
            Runnable runnable = pollElement(1, TimeUnit.DAYS);
            if (runnable != null) {
                return runnable;
            }
        }
    }

    /**
     * Provides a default implementation which just calls {@link #pollElement()} until we reach timeout.
     * <p />
     * Queues supporting this polling element mechanism needs to override this method.
     *
     * @see BlockingQueue#poll(long, TimeUnit)
     * @since 9.3
     */
    protected Runnable pollElement(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.currentTimeMillis() + unit.toMillis(timeout);
        while (timeUntil(end) > 0) {
            Runnable runnable = pollElement();
            if (runnable != null) {
                return runnable;
            }
        }
        return null;
    }

    /**
     * @return the queue metrics
     */
    protected abstract WorkQueueMetrics metrics();

    /**
     * Sets the queue active or inactive. When deactivated, taking an element from the queue (take, poll, peek) behaves
     * as if the queue was empty. Elements can still be added when the queue is deactivated. When reactivated, all
     * elements are again available.
     *
     * @param active {@code true} to make the queue active, or {@code false} to deactivate it
     */
    // TODO is it ok to change signature ? we don't want to compute metrics, could be synchronized and/or heavy
    public void setActive(boolean active) {
        if (active) {
            activate();
        } else {
            deactivate();
        }
    }

    /**
     * Sets the queue active and signal other threads.
     *
     * @see NuxeoBlockingQueue#setActive(boolean)
     * @since 9.3
     */
    public void activate() {
        this.active = true;
        activationLock.lock();
        try {
            activation.signalAll();
        } finally {
            activationLock.unlock();
        }
    }

    /**
     * Sets the queue inactive.
     *
     * @see NuxeoBlockingQueue#setActive(boolean)
     * @since 9.3
     */
    public void deactivate() {
        this.active = false;
    }

    protected long timeUntil(long end) {
        long timeout = end - System.currentTimeMillis();
        if (timeout < 0) {
            timeout = 0;
        }
        return timeout;
    }

    /**
     * @since 9.3
     */
    protected void awaitActivation() throws InterruptedException {
        activationLock.lock();
        try {
            if (!active) {
                activation.await();
            }
        } finally {
            activationLock.unlock();
        }
    }

    protected long awaitActivation(long nanos) throws InterruptedException {
        activationLock.lock();
        try {
            while (nanos > 0 && !active) {
                nanos = activation.awaitNanos(nanos);
            }

        } finally {
            activationLock.unlock();
        }
        return nanos;
    }

}

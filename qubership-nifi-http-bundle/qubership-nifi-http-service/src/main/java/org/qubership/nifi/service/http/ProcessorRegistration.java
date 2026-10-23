/*
 * Copyright 2020-2025 NetCracker Technology Corporation
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

package org.qubership.nifi.service.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Paths, methods, and request queue of one processor registered on QubershipHttpRouterService.
 *
 * <p>A closed registration accepts no more requests, so a request offered while the processor unregisters is either
 * returned by close or refused by offer, and none is left in the queue unanswered.
 *
 * <p>A request that has waited in the queue longer than the queue timeout is expired: poll does not return it, and
 * removeExpired takes it out of the queue. Each expired request is handed to the caller, which has to answer it.
 */
final class ProcessorRegistration {

    /**
     * Result of offering a request to the queue of a processor.
     */
    enum OfferResult {
        /**
         * The request is in the queue.
         */
        QUEUED,
        /**
         * The queue holds its maximum number of requests.
         */
        QUEUE_FULL,
        /**
         * The processor is unregistered.
         */
        CLOSED
    }

    private final String processorId;
    private final Pattern paths;
    private final Set<String> methods;
    private final long queueTimeoutNanos;
    private final BlockingQueue<QueuedRequest> queue;
    private final LongSupplier nanoClock;
    private boolean closed;

    ProcessorRegistration(final String id, final Pattern pathPattern, final Set<String> allowedMethods,
            final int queueSize, final Duration queueTimeout) {
        this(id, pathPattern, allowedMethods, queueSize, queueTimeout, System::nanoTime);
    }

    /**
     * Creates a registration that reads the time at which a request is queued, and the time poll checks it against,
     * from the given clock instead of System.nanoTime, so that a test can move the time on.
     */
    ProcessorRegistration(final String id, final Pattern pathPattern, final Set<String> allowedMethods,
            final int queueSize, final Duration queueTimeout, final LongSupplier clock) {
        this.processorId = id;
        this.paths = pathPattern;
        this.methods = allowedMethods;
        this.queueTimeoutNanos = queueTimeout.toNanos();
        this.queue = new LinkedBlockingQueue<>(queueSize);
        this.nanoClock = clock;
    }

    String getProcessorId() {
        return processorId;
    }

    Pattern getPaths() {
        return paths;
    }

    boolean matchesPath(final String path) {
        return paths.matcher(path).matches();
    }

    Set<String> getMethods() {
        return methods;
    }

    boolean allowsMethod(final String method) {
        return methods.contains(method);
    }

    synchronized OfferResult offer(final HttpRequestContainer container) {
        if (closed) {
            return OfferResult.CLOSED;
        }
        return queue.offer(new QueuedRequest(container, nanoClock.getAsLong()))
                ? OfferResult.QUEUED : OfferResult.QUEUE_FULL;
    }

    /**
     * Takes the next request that has not expired from the queue, waiting up to the given time for one to arrive.
     *
     * @param timeout how long to wait for a request; zero or less returns at once
     * @param unit unit of timeout
     * @param expired list that receives the expired requests taken from the queue on the way
     * @return the next request that has not expired, or null if none arrives within the timeout
     * @throws InterruptedException if the calling thread is interrupted while it waits
     */
    HttpRequestContainer poll(final long timeout, final TimeUnit unit, final List<HttpRequestContainer> expired)
            throws InterruptedException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            final QueuedRequest queued = queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (queued == null) {
                return null;
            }
            if (!isExpired(queued, nanoClock.getAsLong())) {
                return queued.container();
            }
            expired.add(queued.container());
        }
    }

    /**
     * Removes the expired requests from the queue.
     *
     * @param nowNanos current time on the clock of the registration, System.nanoTime by default
     * @return the removed requests, which the caller has to answer
     */
    List<HttpRequestContainer> removeExpired(final long nowNanos) {
        final List<HttpRequestContainer> expired = new ArrayList<>();
        // Requests are queued in the order they arrive, so the first request that has not expired ends the search.
        for (QueuedRequest head = queue.peek(); head != null && isExpired(head, nowNanos); head = queue.peek()) {
            if (queue.remove(head)) {
                expired.add(head.container());
            }
        }
        return expired;
    }

    private boolean isExpired(final QueuedRequest queued, final long nowNanos) {
        return nowNanos - queued.queuedNanos() > queueTimeoutNanos;
    }

    /**
     * Closes the registration and returns the requests still waiting in its queue.
     *
     * @return the requests removed from the queue, which the caller has to answer
     */
    synchronized List<HttpRequestContainer> close() {
        closed = true;
        final List<QueuedRequest> pending = new ArrayList<>();
        queue.drainTo(pending);
        return pending.stream().map(QueuedRequest::container).toList();
    }

    /**
     * Request in the queue, with the time on the clock of the registration at which it was queued.
     *
     * @param container the request
     * @param queuedNanos time on the clock of the registration when the request was queued
     */
    private record QueuedRequest(HttpRequestContainer container, long queuedNanos) {
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * Paths, methods, and request queue of one processor registered on QubershipHttpRouterService.
 *
 * <p>A closed registration accepts no more requests, so a request offered while the processor unregisters is either
 * returned by close or refused by offer, and none is left in the queue unanswered.
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
    private final BlockingQueue<HttpRequestContainer> queue;
    private boolean closed;

    ProcessorRegistration(final String id, final Pattern pathPattern, final Set<String> allowedMethods,
            final int queueSize) {
        this.processorId = id;
        this.paths = pathPattern;
        this.methods = allowedMethods;
        this.queue = new LinkedBlockingQueue<>(queueSize);
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

    boolean allowsMethod(final String method) {
        return methods.contains(method);
    }

    synchronized OfferResult offer(final HttpRequestContainer container) {
        if (closed) {
            return OfferResult.CLOSED;
        }
        return queue.offer(container) ? OfferResult.QUEUED : OfferResult.QUEUE_FULL;
    }

    HttpRequestContainer poll() {
        return queue.poll();
    }

    /**
     * Closes the registration and returns the requests still waiting in its queue.
     *
     * @return the requests removed from the queue, which the caller has to answer
     */
    synchronized List<HttpRequestContainer> close() {
        closed = true;
        final List<HttpRequestContainer> pending = new ArrayList<>();
        queue.drainTo(pending);
        return pending;
    }
}

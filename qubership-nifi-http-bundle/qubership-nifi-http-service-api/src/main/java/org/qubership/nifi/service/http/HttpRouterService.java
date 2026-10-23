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

import org.apache.nifi.controller.ControllerService;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Controller service that runs one HTTP server and routes each incoming request to the queue of a registered
 * processor.
 *
 * <p>A processor registers the paths and methods it handles and then polls its queue for the requests routed to it.
 * When several processors match both the path and the method of a request, the request goes to the processor that
 * registered first. The service does not read the body of a request it routes.
 *
 * <p>A processor calls registerProcessor in its @OnScheduled method and unregisterProcessor in its @OnStopped
 * method, so that only a running processor is registered. NiFi calls @OnStopped after the last onTrigger call of the
 * processor has returned, so no onTrigger call polls the queue after the registration is removed. @OnUnscheduled
 * does not give that guarantee: NiFi calls it while onTrigger calls may still be running.
 *
 * <p>A request waits in the queue until the processor polls it, until it has waited longer than the queue timeout
 * the processor registered with, until the processor is unregistered, or until the service is disabled. The
 * processor sets the time limit of a request it has polled.
 */
public interface HttpRouterService extends ControllerService {

    /**
     * Registers the paths and methods a processor handles and creates its request queue.
     *
     * @param processorId identifier of the processor, the same one it later passes to unregisterProcessor
     *                    and pollRequest
     * @param paths regular expression that a request path must match to be routed to the processor
     * @param methods HTTP methods the processor handles
     * @param queueSize maximum number of requests the queue of the processor holds
     * @param queueTimeout longest time a request waits in the queue of the processor; the service answers a request
     *                     that waits longer itself and never returns it from pollRequest
     * @throws IllegalArgumentException if queueSize or queueTimeout is not positive
     * @throws IllegalStateException if the service is not enabled, if the processor is already registered, or if
     *                               another registered processor has a pattern with the same string as paths.
     *                               Different patterns that match the same path are accepted
     */
    void registerProcessor(String processorId, Pattern paths, Set<String> methods, int queueSize,
            Duration queueTimeout);

    /**
     * Removes the registration and the queue of a processor and answers every request still waiting in the queue
     * with 503 Service Unavailable. Does nothing if the processor is not registered.
     *
     * @param processorId identifier the processor was registered with
     */
    void unregisterProcessor(String processorId);

    /**
     * Returns the next request routed to a processor, waiting up to the given time for one to arrive when the queue
     * is empty.
     *
     * <p>When the calling thread is interrupted while it waits, the method returns null and leaves the interrupt flag
     * of the thread set.
     *
     * @param processorId identifier the processor was registered with
     * @param timeout how long to wait for a request; zero or less returns at once
     * @param unit unit of timeout
     * @return the next request in the queue of the processor, or null if no request arrives within the timeout
     * @throws IllegalStateException if the processor is not registered
     */
    HttpRequestContainer pollRequest(String processorId, long timeout, TimeUnit unit);
}

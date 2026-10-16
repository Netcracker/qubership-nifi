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

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controller service that runs one HTTP server and routes each incoming request to the queue of a registered
 * processor.
 *
 * <p>A processor registers the paths and methods it handles and then polls its queue for the requests routed to it.
 * When the paths of several processors match a request path, the request goes to the processor that registered
 * first. The service does not read the body of a request it routes.
 *
 * <p>The service answers a request itself in these cases:
 * <ul>
 *     <li>404 Not Found when the paths of no registered processor match the request path;</li>
 *     <li>405 Method Not Allowed when the matching processor does not handle the method of the request;</li>
 *     <li>503 Service Unavailable when the queue of the matching processor is full, when the processor is being
 *     unregistered, or when the service is being disabled.</li>
 * </ul>
 *
 * <p>A request waits in the queue with no time limit until the processor polls it, the processor is unregistered, or
 * the service is disabled. The processor sets the time limit of a request it has polled.
 */
public interface HttpRouterService extends ControllerService {

    /**
     * Registers the paths and methods a processor handles and creates its request queue.
     *
     * <p>The service raises the idle timeout of its connections to requestTimeoutMillis when the timeout is
     * shorter.
     *
     * @param processorId identifier of the processor, the same one it later passes to unregisterProcessor
     *                    and pollRequest
     * @param paths regular expression that a request path must match to be routed to the processor
     * @param methods HTTP methods the processor handles
     * @param queueSize maximum number of requests the queue of the processor holds
     * @param requestTimeoutMillis time, in milliseconds, within which the flow answers a request
     * @throws IllegalArgumentException if queueSize is not positive
     * @throws IllegalStateException if the service is not enabled, if the processor is already registered, or if
     *                               another registered processor has a pattern with the same string as paths.
     *                               Different patterns that match the same path are accepted
     */
    void registerProcessor(String processorId, Pattern paths, Set<String> methods, int queueSize,
            long requestTimeoutMillis);

    /**
     * Removes the registration and the queue of a processor and answers every request still waiting in the queue
     * with 503 Service Unavailable. Does nothing if the processor is not registered.
     *
     * @param processorId identifier the processor was registered with
     */
    void unregisterProcessor(String processorId);

    /**
     * Returns the next request routed to a processor without waiting for one to arrive.
     *
     * @param processorId identifier the processor was registered with
     * @return the next request in the queue of the processor, or null if the queue is empty or the processor is
     *         not registered
     */
    HttpRequestContainer pollRequest(String processorId);
}

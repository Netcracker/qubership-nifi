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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A closed registration returns every request still in its queue and refuses every request offered after it, so no
 * request is left in the queue unanswered.
 *
 * <p>The service answers a refused request with 503. Through HTTP, a request is offered after close only when it
 * arrives while the processor is being unregistered, so these tests call the registration directly.
 */
class ProcessorRegistrationTest {

    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessorRegistration registration =
            new ProcessorRegistration("orders", Pattern.compile("/orders"), Set.of("GET"), 5, QUEUE_TIMEOUT);

    @Test
    void aRequestOfferedAfterCloseIsRefusedAsClosed() {
        registration.close();

        assertEquals(ProcessorRegistration.OfferResult.CLOSED, registration.offer(newContainer()),
                "offer after close");
    }

    @Test
    void closeReturnsTheQueuedRequestsAndEmptiesTheQueue() throws Exception {
        final HttpRequestContainer queued = newContainer();
        registration.offer(queued);

        final List<HttpRequestContainer> pending = registration.close();

        assertAll(
                () -> assertEquals(List.of(queued), pending, "requests returned by close"),
                () -> assertNull(registration.poll(0, TimeUnit.MILLISECONDS, new ArrayList<>()), "poll after close")
        );
    }

    /**
     * The fresh request is queued at least 1 ms after the moment the two others are expired against, so it has
     * waited no longer than the queue timeout at that moment.
     */
    @Test
    void removeExpiredTakesOutEveryExpiredRequestInOrderAndLeavesTheFreshOne() throws Exception {
        final HttpRequestContainer first = newContainer();
        final HttpRequestContainer second = newContainer();
        final HttpRequestContainer fresh = newContainer();
        registration.offer(first);
        registration.offer(second);
        final long afterExpiredQueued = System.nanoTime();
        Thread.sleep(1);
        registration.offer(fresh);

        final List<HttpRequestContainer> removed =
                registration.removeExpired(afterExpiredQueued + QUEUE_TIMEOUT.toNanos() + 1);

        // The containers are equal records, so the order is checked by identity.
        assertEquals(2, removed.size(), "number of requests removed after the queue timeout");
        assertAll(
                () -> assertSame(first, removed.get(0), "first request removed"),
                () -> assertSame(second, removed.get(1), "second request removed"),
                () -> assertSame(fresh, registration.poll(0, TimeUnit.MILLISECONDS, new ArrayList<>()),
                        "request polled after removeExpired")
        );
    }

    @Test
    void removeExpiredTakesOutARequestOnlyOnceItHasWaitedLongerThanTheQueueTimeout() throws Exception {
        final HttpRequestContainer queued = newContainer();
        final long queuedAt = System.nanoTime();
        registration.offer(queued);

        final List<HttpRequestContainer> beforeTimeout = registration.removeExpired(queuedAt);
        final List<HttpRequestContainer> afterTimeout =
                registration.removeExpired(System.nanoTime() + QUEUE_TIMEOUT.toNanos() + 1);

        assertAll(
                () -> assertEquals(List.of(), beforeTimeout, "requests removed before the queue timeout"),
                () -> assertEquals(List.of(queued), afterTimeout, "requests removed after the queue timeout"),
                () -> assertNull(registration.poll(0, TimeUnit.MILLISECONDS, new ArrayList<>()),
                        "poll after the expired request was removed")
        );
    }

    @Test
    void pollHandsAnExpiredRequestToTheCallerInsteadOfReturningIt() throws Exception {
        final ProcessorRegistration shortTimeout = new ProcessorRegistration("orders", Pattern.compile("/orders"),
                Set.of("GET"), 5, Duration.ofMillis(1));
        final HttpRequestContainer queued = newContainer();
        shortTimeout.offer(queued);
        Thread.sleep(10);
        final List<HttpRequestContainer> expired = new ArrayList<>();

        final HttpRequestContainer polled = shortTimeout.poll(0, TimeUnit.MILLISECONDS, expired);

        assertAll(
                () -> assertNull(polled, "request returned by poll 10 ms after it was queued with a 1 ms timeout"),
                () -> assertEquals(List.of(queued), expired, "expired requests handed to the caller")
        );
    }

    @Test
    void pollSkipsAnExpiredRequestAndReturnsTheNextOne() throws Exception {
        final ProcessorRegistration shortTimeout = new ProcessorRegistration("orders", Pattern.compile("/orders"),
                Set.of("GET"), 5, Duration.ofMillis(200));
        final HttpRequestContainer expiredRequest = newContainer();
        final HttpRequestContainer freshRequest = newContainer();
        shortTimeout.offer(expiredRequest);
        Thread.sleep(300);
        shortTimeout.offer(freshRequest);
        final List<HttpRequestContainer> expired = new ArrayList<>();

        final HttpRequestContainer polled = shortTimeout.poll(0, TimeUnit.MILLISECONDS, expired);

        assertAll(
                () -> assertSame(freshRequest, polled, "request returned by poll after an expired one"),
                () -> assertEquals(List.of(expiredRequest), expired, "expired requests handed to the caller")
        );
    }

    private static HttpRequestContainer newContainer() {
        return new HttpRequestContainer(null, null, null);
    }
}

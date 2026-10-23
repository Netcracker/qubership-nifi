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
import java.util.concurrent.atomic.AtomicLong;
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
    private static final long TIMEOUT_NANOS = QUEUE_TIMEOUT.toNanos();

    // Time in nanoseconds on the clock of the registration; a test moves it on instead of waiting.
    private final AtomicLong now = new AtomicLong();
    private final ProcessorRegistration registration = new ProcessorRegistration("orders", Pattern.compile("/orders"),
            Set.of("GET"), 5, QUEUE_TIMEOUT, now::get);

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

    @Test
    void removeExpiredTakesOutEveryExpiredRequestInOrderAndLeavesTheFreshOne() {
        final HttpRequestContainer first = newContainer();
        final HttpRequestContainer second = newContainer();
        final HttpRequestContainer fresh = newContainer();
        registration.offer(first);
        registration.offer(second);
        now.set(1);
        registration.offer(fresh);

        final List<HttpRequestContainer> removed = registration.removeExpired(TIMEOUT_NANOS + 1);

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
        registration.offer(queued);

        final List<HttpRequestContainer> atTimeout = registration.removeExpired(TIMEOUT_NANOS);
        final List<HttpRequestContainer> afterTimeout = registration.removeExpired(TIMEOUT_NANOS + 1);

        assertAll(
                () -> assertEquals(List.of(), atTimeout, "requests removed after exactly the queue timeout"),
                () -> assertEquals(List.of(queued), afterTimeout, "requests removed after the queue timeout"),
                () -> assertNull(registration.poll(0, TimeUnit.MILLISECONDS, new ArrayList<>()),
                        "poll after the expired request was removed")
        );
    }

    @Test
    void pollHandsAnExpiredRequestToTheCallerInsteadOfReturningIt() throws Exception {
        final HttpRequestContainer queued = newContainer();
        registration.offer(queued);
        now.set(TIMEOUT_NANOS + 1);
        final List<HttpRequestContainer> expired = new ArrayList<>();

        final HttpRequestContainer polled = registration.poll(0, TimeUnit.MILLISECONDS, expired);

        assertAll(
                () -> assertNull(polled, "request returned by poll after the queue timeout"),
                () -> assertEquals(List.of(queued), expired, "expired requests handed to the caller")
        );
    }

    @Test
    void pollSkipsAnExpiredRequestAndReturnsTheNextOne() throws Exception {
        final HttpRequestContainer expiredRequest = newContainer();
        final HttpRequestContainer freshRequest = newContainer();
        registration.offer(expiredRequest);
        now.set(TIMEOUT_NANOS + 1);
        registration.offer(freshRequest);
        final List<HttpRequestContainer> expired = new ArrayList<>();

        final HttpRequestContainer polled = registration.poll(0, TimeUnit.MILLISECONDS, expired);

        assertAll(
                () -> assertSame(freshRequest, polled, "request returned by poll after an expired one"),
                () -> assertEquals(List.of(expiredRequest), expired, "expired requests handed to the caller")
        );
    }

    private static HttpRequestContainer newContainer() {
        return new HttpRequestContainer(null, null, null);
    }
}

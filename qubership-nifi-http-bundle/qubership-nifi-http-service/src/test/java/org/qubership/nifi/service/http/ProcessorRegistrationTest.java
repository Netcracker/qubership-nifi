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

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A closed registration returns every request still in its queue and refuses every request offered after it, so no
 * request is left in the queue unanswered.
 *
 * <p>The service answers a refused request with 503. Through HTTP, a request is offered after close only when it
 * arrives while the processor is being unregistered, so these tests call the registration directly.
 */
class ProcessorRegistrationTest {

    private final ProcessorRegistration registration =
            new ProcessorRegistration("orders", Pattern.compile("/orders"), Set.of("GET"), 5);

    @Test
    void aRequestOfferedAfterCloseIsRefusedAsClosed() {
        registration.close();

        assertEquals(ProcessorRegistration.OfferResult.CLOSED, registration.offer(newContainer()),
                "offer after close");
    }

    @Test
    void closeReturnsTheQueuedRequestsAndEmptiesTheQueue() {
        final HttpRequestContainer queued = newContainer();
        registration.offer(queued);

        final List<HttpRequestContainer> pending = registration.close();

        assertAll(
                () -> assertEquals(List.of(queued), pending, "requests returned by close"),
                () -> assertNull(registration.poll(), "poll after close")
        );
    }

    private static HttpRequestContainer newContainer() {
        return new HttpRequestContainer(null, null, null);
    }
}

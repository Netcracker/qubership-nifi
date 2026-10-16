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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One HTTP request that HttpRouterService routed to a processor, together with the objects needed to answer it.
 *
 * <p>The request is in asynchronous mode and its body is unread. It stays open until the response is written and
 * the asynchronous context is completed.
 *
 * @param request the request, with its body unread
 * @param response the response to write the answer to
 * @param context the asynchronous context to complete once the response is written
 */
public record HttpRequestContainer(HttpServletRequest request, HttpServletResponse response, AsyncContext context) {
}

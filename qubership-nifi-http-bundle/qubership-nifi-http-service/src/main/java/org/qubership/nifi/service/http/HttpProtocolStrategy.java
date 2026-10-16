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

import org.apache.nifi.components.DescribedValue;
import org.apache.nifi.jetty.configuration.connector.ApplicationLayerProtocol;

import java.util.Set;

/**
 * HTTP protocols a server offers for Application Layer Protocol Negotiation with TLS.
 */
public enum HttpProtocolStrategy implements DescribedValue {
    /**
     * HTTP/1.1 only.
     */
    HTTP_1_1("http/1.1", "HTTP/1.1", Set.of(ApplicationLayerProtocol.HTTP_1_1)),
    /**
     * HTTP/2 or HTTP/1.1, whichever the client requests.
     */
    H2_HTTP_1_1("h2 http/1.1", "HTTP/2 and HTTP/1.1 negotiated based on requested protocols",
            Set.of(ApplicationLayerProtocol.HTTP_1_1, ApplicationLayerProtocol.H2)),
    /**
     * HTTP/2 only.
     */
    H2("h2", "HTTP/2", Set.of(ApplicationLayerProtocol.H2));

    private final String displayName;
    private final String description;
    private final Set<ApplicationLayerProtocol> applicationLayerProtocols;

    HttpProtocolStrategy(final String displayNameValue, final String descriptionValue,
            final Set<ApplicationLayerProtocol> protocols) {
        this.displayName = displayNameValue;
        this.description = descriptionValue;
        this.applicationLayerProtocols = protocols;
    }

    @Override
    public String getValue() {
        return name();
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Returns the protocols to configure on the server connector.
     *
     * @return application layer protocols of this strategy
     */
    public Set<ApplicationLayerProtocol> getApplicationLayerProtocols() {
        return applicationLayerProtocols;
    }
}

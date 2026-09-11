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

package org.qubership.nifi.tools.kb;

import org.junit.jupiter.api.Test;

/** Runs the same full, catalog, fingerprint, and cleanup contract against NiFi 1.28.1. */
class KnowledgeBaseBuilder1xIT extends KnowledgeBaseBuilderIT {
    @Test
    void collectsServiceMetadataAtGroupAndControllerScope() throws Exception {
        verifyControllerServiceScopes();
    }

    @Override
    protected String image() {
        return "apache/nifi:1.28.1";
    }

    @Override
    protected int nifiMajorVersion() {
        return 1;
    }

    @Override
    protected int port() {
        return 19445;
    }
}

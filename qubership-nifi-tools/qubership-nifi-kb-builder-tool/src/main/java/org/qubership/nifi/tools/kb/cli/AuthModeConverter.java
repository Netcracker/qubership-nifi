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

package org.qubership.nifi.tools.kb.cli;

import picocli.CommandLine;

/**
 * Converts the {@code --auth} value to an {@link AuthMode}. Delegating to {@link AuthMode#parse}
 * keeps the lower-case {@code token}, {@code cookie}, and {@code certificate} spellings and the
 * existing rejection message, rather than picocli's default enum matching on constant names.
 */
public final class AuthModeConverter implements CommandLine.ITypeConverter<AuthMode> {

    /**
     * Converts a raw option value.
     *
     * @param value the raw {@code --auth} value
     * @return the parsed mode
     */
    @Override
    public AuthMode convert(final String value) {
        return AuthMode.parse(value);
    }
}

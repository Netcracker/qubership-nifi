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

package org.qubership.nifi.tools.kb.model;

/**
 * The three guides collected from the target NiFi instance. Each constant carries the guide title,
 * the source path on the NiFi deployment base, the output file name, and the manifest key. Keeping
 * the source paths here isolates them from collection code so a future compatibility adapter can
 * change them without touching the output contract or CLI.
 */
public enum GuideType {

    /** The Expression Language Guide. */
    EXPRESSION_LANGUAGE("Expression Language Guide",
            "/nifi-api/html/expression-language-guide.html",
            "expression-language-guide.md", "expressionLanguageGuide", "Expression Language"),

    /** The RecordPath Guide. */
    RECORD_PATH("RecordPath Guide",
            "/nifi-api/html/record-path-guide.html",
            "record-path-guide.md", "recordPathGuide", "RecordPath"),

    /** The Developer's Guide. */
    DEVELOPER("Developer's Guide",
            "/nifi-api/html/developer-guide.html",
            "developer-guide.md", "developerGuide", "Developer");

    private final String title;
    private final String sourcePath;
    private final String outputFileName;
    private final String manifestKey;
    private final String sentinelKeyword;

    GuideType(final String guideTitle, final String path, final String fileName, final String key,
              final String sentinel) {
        this.title = guideTitle;
        this.sourcePath = path;
        this.outputFileName = fileName;
        this.manifestKey = key;
        this.sentinelKeyword = sentinel;
    }

    /**
     * Returns a keyword expected to appear in a valid guide page. It is used as a sentinel to fail
     * fast when a path returns the single-page-app shell instead of the guide.
     *
     * @return the sentinel keyword
     */
    public String getSentinelKeyword() {
        return sentinelKeyword;
    }

    /**
     * Returns the guide title.
     *
     * @return the title
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the source path on the NiFi deployment base.
     *
     * @return the source path
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Returns the output file name under {@code guides/}.
     *
     * @return the output file name
     */
    public String getOutputFileName() {
        return outputFileName;
    }

    /**
     * Returns the relative output path under the Knowledge Base root.
     *
     * @return the relative output path
     */
    public String getOutputPath() {
        return "guides/" + outputFileName;
    }

    /**
     * Returns the manifest key for this guide.
     *
     * @return the manifest key
     */
    public String getManifestKey() {
        return manifestKey;
    }
}

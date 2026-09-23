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
package org.qubership.nifi.dev.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.nifi.NifiAccessPolicies;
import org.qubership.nifi.NifiFlowApiClient;
import org.qubership.nifi.NifiMtlsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rule named in the configuration ends up in the target NiFi with the configured enforcement
 * policy and property values, whether the script had to create it, update it, or leave it alone.
 *
 * <p>{@code dev/flow-rules-script/createFlowRules.sh} runs through bash on the host, against the
 * NiFi and the Consul of the {@code oidc} compose stack. It reads its configuration from the Consul
 * key {@code config/local/qubership-nifi/flow-analysis-rules} when {@code CONSUL_URL} is set
 * and the key exists, and from the file named on its command line otherwise.
 *
 * <p>Every fixture uses the built-in {@code RestrictBackpressureSettings} rule type, which every
 * Apache NiFi 2.x ships. The custom qubership-nifi rule set has grown across the image versions in
 * the test matrix, so a fixture naming one of those would pass or fail depending on the image
 * rather than on the script. Flow analysis rules are controller-level state, so each test gives its
 * rules a random name prefix and {@link #deleteRulesOfThisTest()} removes them.
 *
 * <p>Two things this suite does not establish. Consul runs with ACLs disabled here, so no test
 * shows that a real token is accepted or that a wrong one is rejected;
 * {@link #prefersTheConsulConfigurationOverTheFile()} only shows the token header is well-formed.
 * And the shipped {@code flowAnalysisRuleConf.json} is not exercised, because its custom rule types
 * exist only in the locally built image.
 *
 * <p>Requires the {@code nifi.cert.dir} system property, the {@code NIFI_CLIENT_PASSWORD}
 * environment variable, and a reachable Consul at {@code consul.url}.
 */
final class CreateFlowRulesIT {

    private static final Logger LOG = LoggerFactory.getLogger(CreateFlowRulesIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCRIPT_RELATIVE_PATH = "dev/flow-rules-script/createFlowRules.sh";
    private static final String ADMIN_CERT_FILENAME = "CN=admin_OU=NIFI.p12";
    private static final String NIFI_CA_CERT_FILENAME = "nifi-cert.pem";

    /** Built-in rule type every fixture uses; present in every Apache NiFi 2.x. */
    private static final String RULE_TYPE = "org.apache.nifi.flowanalysis.rules.RestrictBackpressureSettings";
    private static final String MIN_COUNT = "Minimum Backpressure Object Count Threshold";
    private static final String MAX_COUNT = "Maximum Backpressure Object Count Threshold";
    private static final String MIN_SIZE = "Minimum Backpressure Data Size Threshold";
    private static final String MAX_SIZE = "Maximum Backpressure Data Size Threshold";

    /** Namespace segment of the Consul key, matching the {@code NAMESPACE} of the compose stack. */
    private static final String NAMESPACE = "local";
    private static final String CONSUL_KEY = "config/" + NAMESPACE + "/qubership-nifi/flow-analysis-rules";

    private static String nifiUrl;
    private static String nifiCert;
    private static String consulUrl;
    private static NifiFlowApiClient api;
    private static ConsulKvClient consul;
    private static BashScriptRunner runner;

    /**
     * Name prefix of the rules of one test. Flow analysis rules are controller-level state and a
     * teardown can fail, so a prefix per test keeps a rule left behind from colliding with the next
     * one and failing it for an unrelated reason.
     */
    private String namePrefix;
    private Path workDir;

    @BeforeAll
    static void setUpTargets() throws Exception {
        String certDir = System.getProperty("nifi.cert.dir");
        assertNotNull(certDir, "Set the nifi.cert.dir system property to the directory holding "
            + ADMIN_CERT_FILENAME + " and " + NIFI_CA_CERT_FILENAME);
        String certPassword = System.getenv("NIFI_CLIENT_PASSWORD");
        assertNotNull(certPassword, "Set the NIFI_CLIENT_PASSWORD environment variable to the password of "
            + ADMIN_CERT_FILENAME);

        nifiUrl = System.getProperty("nifi.url", "https://localhost:8080");
        consulUrl = System.getProperty("consul.url", "http://localhost:8500");
        Path certPath = Path.of(certDir, ADMIN_CERT_FILENAME);
        Path caPath = Path.of(certDir, NIFI_CA_CERT_FILENAME);
        //The script hands NIFI_CERT to curl through eval, so the quoting here is the quoting curl sees.
        nifiCert = "--cert '" + BashScriptRunner.forBash(certPath) + ":" + certPassword + "'"
            + " --cert-type P12 --cacert " + BashScriptRunner.forBash(caPath);

        HttpClient httpClient = NifiMtlsClient.build(certPath.toString(), certPassword, caPath.toString());
        api = new NifiFlowApiClient(nifiUrl, httpClient);
        new NifiAccessPolicies(nifiUrl, httpClient).setup();
        consul = new ConsulKvClient(consulUrl, httpClient);
        runner = BashScriptRunner.forScript(SCRIPT_RELATIVE_PATH, "jq", "curl");

        assertTrue(isRuleTypeInstalled(), RULE_TYPE + " is not installed in the NiFi at " + nifiUrl
            + ". It is a built-in Apache NiFi rule type, so this NiFi is either older than 2.0 or "
            + "missing its standard rules, and no fixture in this class can be created.");
    }

    @BeforeEach
    void createWorkDirAndNamePrefix() throws IOException {
        workDir = Files.createTempDirectory("flow-rules-it-");
        namePrefix = "IT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        LOG.info("Flow analysis rules of this test are named {}*", namePrefix);
    }

    @AfterEach
    void deleteRulesOfThisTest() throws Exception {
        for (JsonNode rule : api.getFlowAnalysisRules()) {
            String name = rule.path("component").path("name").asText();
            if (!name.startsWith(namePrefix)) {
                continue;
            }
            String id = rule.path("id").asText();
            String version = rule.path("revision").path("version").asText();
            if (!"DISABLED".equals(rule.path("component").path("state").asText())) {
                version = api.setFlowAnalysisRuleState(id, version, "DISABLED")
                        .path("revision").path("version").asText(version);
                api.waitForFlowAnalysisRuleState(id, "DISABLED");
                version = api.getFlowAnalysisRuleById(id).path("revision").path("version").asText(version);
            }
            api.deleteFlowAnalysisRule(id, version);
            assertNull(api.findFlowAnalysisRuleByName(name),
                "Teardown left rule " + name + " behind; a later test would fail on state this one created");
            LOG.info("Deleted flow analysis rule {}", name);
        }
        consul.delete(CONSUL_KEY);
        deleteRecursively(workDir);
    }

    // -------------------------------------------------------------------------
    // Creating
    // -------------------------------------------------------------------------

    @Test
    void createsAndEnablesEveryRuleFromTheFile() throws Exception {
        Path config = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")),
            rule(withoutProperties(), "Enforce", Map.of()));

        ScriptRun run = runScript(config);

        JsonNode configured = requireRule(withProperties());
        JsonNode defaulted = requireRule(withoutProperties());
        assertAll("two rules created from " + config.getFileName(),
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertTrue(run.output().contains("Done. Created: 2, updated: 0, skipped (unchanged): 0."),
                () -> "Missing summary:\n" + run.output()),
            () -> assertEquals("WARN", policyOf(configured)),
            () -> assertEquals("20000", propertyOf(configured, MAX_COUNT)),
            () -> assertEquals("ENABLED", stateOf(configured)),
            () -> assertEquals("ENFORCE", policyOf(defaulted)),
            () -> assertEquals("ENABLED", stateOf(defaulted)));
    }

    @Test
    void failsWhenTheRuleTypeIsNotInstalled() throws Exception {
        ObjectNode entry = rule(withProperties(), "Warn", Map.of());
        entry.put("Type", "org.example.NoSuchFlowAnalysisRule");
        Path config = writeConfig("bad-type.json", entry);

        ScriptRun run = runScript(config);

        assertAll("an uninstalled rule type is rejected",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains("org.example.NoSuchFlowAnalysisRule"),
                () -> "The error must name the type:\n" + run.output()),
            () -> assertNull(api.findFlowAnalysisRuleByName(withProperties()),
                "No rule must be created when the type is not installed"));
    }

    @Test
    void failsWhenTheConfigurationFileIsMissing() {
        ScriptRun run = runner.run(workDir, scriptEnvironment(), "./there-is-no-such-file.json");

        assertAll("a missing configuration file is rejected",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains("./there-is-no-such-file.json"),
                () -> "The error must name the file:\n" + run.output()));
    }

    @Test
    void failsWhenNoConfigurationFileIsGiven() {
        ScriptRun run = runner.run(workDir, scriptEnvironment());

        assertAll("the configuration file argument is required",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains("Usage: bash createFlowRules.sh <pathToConfig>"),
                () -> "The error must show the usage:\n" + run.output()));
    }

    /**
     * Reads the rules from Consul, so that the temporary file the Consul path writes is one of the
     * files the assertion covers. With {@code CONSUL_URL} empty the script returns before creating
     * it, and this test would be blind to it.
     */
    @Test
    void removesItsTemporaryFilesFromTheWorkingDirectory() throws Exception {
        Path config = writeConfig("rules.json", rule(withProperties(), "Warn", Map.of()));
        consul.put(CONSUL_KEY, configJson(rule(withProperties(), "Warn", Map.of())));

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl);
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(config));

        assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output());
        try (Stream<Path> files = Files.list(workDir)) {
            assertEquals(Set.of("rules.json"), files.map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet()),
                "Only the configuration file may remain; the script deletes every temporary file it writes");
        }
    }

    // -------------------------------------------------------------------------
    // Updating an existing rule
    // -------------------------------------------------------------------------

    @Test
    void leavesARuleThatMatchesTheConfigurationUntouched() throws Exception {
        Path config = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        ScriptRun create = runScript(config);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());
        String revisionAfterCreate = revisionOf(requireRule(withProperties()));

        ScriptRun run = runScript(config);

        assertAll("a second run over the same configuration changes nothing",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertTrue(run.output().contains("Done. Created: 0, updated: 0, skipped (unchanged): 1."),
                () -> "Missing summary:\n" + run.output()),
            //The revision is what shows no request was sent: NiFi bumps it on every change.
            () -> assertEquals(revisionAfterCreate, revisionOf(requireRule(withProperties())),
                "The revision must not move when the rule already matches"));
    }

    @Test
    void updatesThePolicyAndThePropertyOfAnExistingRule() throws Exception {
        Path created = writeConfig("created.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        ScriptRun create = runScript(created);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());

        Path changed = writeConfig("changed.json",
            rule(withProperties(), "Enforce", Map.of(MIN_COUNT, "1", MAX_COUNT, "12345")));
        ScriptRun run = runScript(changed);

        JsonNode rule = requireRule(withProperties());
        assertAll("the rule is brought in line with the configuration",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertEquals("ENFORCE", policyOf(rule)),
            () -> assertEquals("12345", propertyOf(rule, MAX_COUNT)),
            () -> assertEquals("ENABLED", stateOf(rule), "The rule must be enabled again after the update"),
            () -> assertTrue(run.output().contains("Policy: WARN -> ENFORCE"),
                () -> "The run must report the changed policy:\n" + run.output()),
            () -> assertTrue(run.output().contains(MAX_COUNT + ": 20000 -> 12345"),
                () -> "The run must report the changed property:\n" + run.output()),
            () -> assertTrue(run.output().contains("Done. Created: 0, updated: 1, skipped (unchanged): 0."),
                () -> "Missing summary:\n" + run.output()));
    }

    @Test
    void keepsThePropertiesTheConfigurationDoesNotList() throws Exception {
        Path created = writeConfig("created.json", rule(withProperties(), "Warn",
            Map.of(MIN_COUNT, "1", MAX_COUNT, "20000", MIN_SIZE, "2 MB", MAX_SIZE, "3 GB")));
        ScriptRun create = runScript(created);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());

        Path changed = writeConfig("changed.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "12345")));
        ScriptRun run = runScript(changed);

        JsonNode rule = requireRule(withProperties());
        assertAll("an update replaces only the properties the entry lists",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertEquals("12345", propertyOf(rule, MAX_COUNT)),
            () -> assertEquals("2 MB", propertyOf(rule, MIN_SIZE)),
            () -> assertEquals("3 GB", propertyOf(rule, MAX_SIZE)));
    }

    @Test
    void reEnablesARuleLeftDisabled() throws Exception {
        Path config = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        ScriptRun create = runScript(config);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());
        JsonNode created = requireRule(withProperties());
        String id = created.path("id").asText();
        api.setFlowAnalysisRuleState(id, revisionOf(created), "DISABLED");
        api.waitForFlowAnalysisRuleState(id, "DISABLED");

        ScriptRun run = runScript(config);

        assertAll("a rule left disabled is enabled again without being updated",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertEquals("ENABLED", stateOf(requireRule(withProperties()))),
            () -> assertTrue(run.output().contains("Done. Created: 0, updated: 0, skipped (unchanged): 1."),
                () -> "The rule matches the configuration, so it is skipped, not updated:\n" + run.output()));
    }

    /**
     * NiFi fixes a rule's type when it creates the rule, so the script cannot carry out an entry
     * that reuses the name of a rule of another type.
     */
    @Test
    void failsWhenTheConfiguredTypeDiffersFromTheExistingRule() throws Exception {
        Path created = writeConfig("created.json", rule(withProperties(), "Warn", Map.of()));
        ScriptRun create = runScript(created);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());

        ObjectNode entry = rule(withProperties(), "Warn", Map.of());
        entry.put("Type", "org.apache.nifi.flowanalysis.rules.DisallowComponentType");
        ScriptRun run = runScript(writeConfig("other-type.json", entry));

        assertAll("a rule of another type under the same name is rejected",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains(RULE_TYPE),
                () -> "The error must name the type the rule has:\n" + run.output()),
            () -> assertTrue(run.output().contains("DisallowComponentType"),
                () -> "The error must name the type the configuration sets:\n" + run.output()),
            () -> assertEquals(RULE_TYPE, requireRule(withProperties()).path("component").path("type").asText(),
                "The existing rule must be left as it is"));
    }

    /**
     * A property value the configuration writes as a number reaches NiFi as the string NiFi stores,
     * so it matches on the next run. Sent as a number it would differ from the stored string every
     * time, and the script would disable, update and enable the rule on every run.
     */
    @Test
    void treatsANumericPropertyValueAsTheStringNifiStores() throws Exception {
        ObjectNode entry = rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1"));
        entry.withArray("Property").addObject().put("name", MAX_COUNT).put("value", 20000);
        Path config = writeConfig("numeric.json", entry);
        ScriptRun create = runScript(config);
        assertEquals(0, create.exitCode(), () -> "The first run must create the rule:\n" + create.output());
        String revisionAfterCreate = revisionOf(requireRule(withProperties()));

        ScriptRun run = runScript(config);

        assertAll("a numeric value is stored and compared as a string",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertEquals("20000", propertyOf(requireRule(withProperties()), MAX_COUNT)),
            () -> assertEquals(revisionAfterCreate, revisionOf(requireRule(withProperties())),
                "A numeric value must not make the rule differ from itself on every run"),
            () -> assertTrue(run.output().contains("Done. Created: 0, updated: 0, skipped (unchanged): 1."),
                () -> "Missing summary:\n" + run.output()));
    }

    // -------------------------------------------------------------------------
    // Choosing between Consul and the file
    // -------------------------------------------------------------------------

    @Test
    void prefersTheConsulConfigurationOverTheFile() throws Exception {
        Path file = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        consul.put(CONSUL_KEY, configJson(
            rule(withProperties(), "Enforce", Map.of(MIN_COUNT, "1", MAX_COUNT, "12345"))));

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl);
        //ACLs are off on this Consul, so the token is ignored. The run succeeding is what shows the
        //script builds a well-formed X-Consul-Token header rather than a broken curl argument.
        environment.put("CONSUL_ACL_TOKEN", "a-token-this-consul-ignores");
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(file));

        JsonNode rule = requireRule(withProperties());
        assertAll("the Consul key wins over the file",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertTrue(run.output().contains("Reading the rules from Consul key '" + CONSUL_KEY + "'."),
                () -> "The run must say it read Consul:\n" + run.output()),
            () -> assertEquals("ENFORCE", policyOf(rule), "The policy must come from Consul, not from the file"),
            () -> assertEquals("12345", propertyOf(rule, MAX_COUNT)));
    }

    @Test
    void fallsBackToTheFileWhenTheConsulKeyIsAbsent() throws Exception {
        Path file = writeConfig("rules.json",
            rule(withProperties(), "Enforce", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        consul.delete(CONSUL_KEY);

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl);
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(file));

        JsonNode rule = requireRule(withProperties());
        assertAll("an absent key falls back to the file",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertTrue(run.output().contains("Consul has no key '" + CONSUL_KEY + "'."),
                () -> "The run must name the key it looked for:\n" + run.output()),
            () -> assertEquals("ENFORCE", policyOf(rule)),
            () -> assertEquals("20000", propertyOf(rule, MAX_COUNT)));
    }

    @Test
    void failsWhenTheConsulValueIsNotAJsonArray() throws Exception {
        Path file = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        consul.put(CONSUL_KEY, "not-json");

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl);
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(file));

        assertAll("a key holding an unusable value fails the run instead of falling back",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains("is not a JSON array of rules"),
                () -> "The error must say what is wrong with the value:\n" + run.output()),
            () -> assertNull(api.findFlowAnalysisRuleByName(withProperties()),
                "The file must not be used as a fallback for a key that holds an unusable value"));
    }

    /**
     * A JSON object is the partition {@code not-json} does not reach: it parses, and only the type
     * check rejects it.
     */
    @Test
    void failsWhenTheConsulValueIsJsonButNotAnArray() throws Exception {
        Path file = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        consul.put(CONSUL_KEY, "{\"Name\": \"a single rule, not an array of them\"}");

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl);
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(file));

        assertAll("a JSON object is rejected like any other value that is not an array",
            () -> assertNotEquals(0, run.exitCode(), () -> "Expected a failure:\n" + run.output()),
            () -> assertTrue(run.output().contains("is not a JSON array of rules"),
                () -> "The error must say what is wrong with the value:\n" + run.output()),
            () -> assertNull(api.findFlowAnalysisRuleByName(withProperties()),
                "The file must not be used as a fallback for a key that holds an unusable value"));
    }

    /**
     * {@code CONSUL_URL} is documented, and set by the compose stacks, both with and without a
     * scheme, so the script has to accept the bare {@code <hostname>:<port>} form too.
     */
    @Test
    void readsConsulWhenTheUrlCarriesNoScheme() throws Exception {
        Path file = writeConfig("rules.json",
            rule(withProperties(), "Warn", Map.of(MIN_COUNT, "1", MAX_COUNT, "20000")));
        consul.put(CONSUL_KEY, configJson(
            rule(withProperties(), "Enforce", Map.of(MIN_COUNT, "1", MAX_COUNT, "12345"))));

        Map<String, String> environment = scriptEnvironment();
        environment.put("CONSUL_URL", consulUrl.replaceFirst("^https?://", ""));
        ScriptRun run = runner.run(workDir, environment, BashScriptRunner.forBash(file));

        JsonNode rule = requireRule(withProperties());
        assertAll("a scheme-less CONSUL_URL reaches the same key",
            () -> assertEquals(0, run.exitCode(), () -> "Script failed:\n" + run.output()),
            () -> assertTrue(run.output().contains("Reading the rules from Consul key '" + CONSUL_KEY + "'."),
                () -> "The run must say it read Consul:\n" + run.output()),
            () -> assertEquals("ENFORCE", policyOf(rule)),
            () -> assertEquals("12345", propertyOf(rule, MAX_COUNT)));
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private String withProperties() {
        return namePrefix + "Backpressure";
    }

    private String withoutProperties() {
        return namePrefix + "BackpressureDefaults";
    }

    private static ObjectNode rule(final String name, final String policy, final Map<String, String> properties) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("Name", name);
        entry.put("Type", RULE_TYPE);
        entry.put("Policy", policy);
        ArrayNode propertyArray = entry.putArray("Property");
        for (Map.Entry<String, String> property : new LinkedHashMap<>(properties).entrySet()) {
            ObjectNode node = propertyArray.addObject();
            node.put("name", property.getKey());
            node.put("value", property.getValue());
        }
        return entry;
    }

    private static String configJson(final ObjectNode... rules) throws IOException {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode entry : rules) {
            array.add(entry);
        }
        return MAPPER.writeValueAsString(array);
    }

    private Path writeConfig(final String fileName, final ObjectNode... rules) throws IOException {
        Path config = workDir.resolve(fileName);
        Files.writeString(config, configJson(rules), StandardCharsets.UTF_8);
        return config;
    }

    // -------------------------------------------------------------------------
    // Running the script and reading the result
    // -------------------------------------------------------------------------

    /**
     * Returns the environment every run needs. {@code CONSUL_URL} is empty, so the script reads the
     * file; the tests that exercise Consul overwrite it.
     *
     * @return a mutable environment for {@link BashScriptRunner#run}
     */
    private static Map<String, String> scriptEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("NIFI_TARGET_URL", nifiUrl);
        environment.put("NIFI_CERT", nifiCert);
        environment.put("NAMESPACE", NAMESPACE);
        environment.put("CONSUL_URL", "");
        environment.put("CONSUL_ACL_TOKEN", "");
        return environment;
    }

    private ScriptRun runScript(final Path config) {
        return runner.run(workDir, scriptEnvironment(), BashScriptRunner.forBash(config));
    }

    /**
     * Returns the rule with the given name, failing with the names that do exist when it is
     * missing.
     *
     * @param name rule name
     * @return the rule entity
     */
    private JsonNode requireRule(final String name) throws Exception {
        JsonNode rule = api.findFlowAnalysisRuleByName(name);
        assertNotNull(rule, () -> {
            StringBuilder names = new StringBuilder();
            for (JsonNode candidate : rulesOrEmpty()) {
                names.append(candidate.path("component").path("name").asText()).append(' ');
            }
            return "No flow analysis rule named " + name + "; the controller has: " + names;
        });
        return rule;
    }

    private static JsonNode rulesOrEmpty() {
        try {
            return api.getFlowAnalysisRules();
        } catch (IOException | InterruptedException e) {
            return MAPPER.createArrayNode();
        }
    }

    private static String policyOf(final JsonNode rule) {
        return rule.path("component").path("enforcementPolicy").asText();
    }

    private static String stateOf(final JsonNode rule) {
        return rule.path("component").path("state").asText();
    }

    private static String revisionOf(final JsonNode rule) {
        return rule.path("revision").path("version").asText();
    }

    private static String propertyOf(final JsonNode rule, final String property) {
        return rule.path("component").path("properties").path(property).asText();
    }

    private static boolean isRuleTypeInstalled() throws IOException, InterruptedException {
        for (JsonNode type : api.fetchFlowAnalysisRuleTypes()) {
            if (RULE_TYPE.equals(type.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursively(final Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (!path.toFile().delete()) {
                    LOG.warn("Could not delete {}", path);
                }
            });
        }
    }
}

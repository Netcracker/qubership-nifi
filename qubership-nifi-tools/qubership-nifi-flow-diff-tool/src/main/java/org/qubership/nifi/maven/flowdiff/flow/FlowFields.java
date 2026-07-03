package org.qubership.nifi.maven.flowdiff.flow;

/**
 * The NiFi versioned-flow JSON field and section names shared across the tool. Centralizing them here keeps the
 * comparison, revert, and indexing code reading and writing the exact same keys, and gives every reference a single
 * definition to import statically rather than a literal redeclared per class.
 */
public final class FlowFields {

    /** The {@code identifier} field: a component's stable identity across exports. */
    public static final String IDENTIFIER = "identifier";
    /** The {@code instanceIdentifier} field: a component's per-instance technical identifier. */
    public static final String INSTANCE_IDENTIFIER = "instanceIdentifier";
    /** The {@code groupIdentifier} field: a component's back-reference to its enclosing process group. */
    public static final String GROUP_IDENTIFIER = "groupIdentifier";
    /** The {@code groupId} field: a connection endpoint's enclosing-group reference. */
    public static final String GROUP_ID = "groupId";
    /** The {@code id} field: a connection endpoint's referenced-component id. */
    public static final String ID = "id";
    /** The {@code name} field. */
    public static final String NAME = "name";
    /** The {@code type} field: a component or endpoint type. */
    public static final String TYPE = "type";
    /** The {@code position} field: a component's canvas coordinates. */
    public static final String POSITION = "position";

    /** The {@code source} connection endpoint role. */
    public static final String SOURCE = "source";
    /** The {@code destination} connection endpoint role. */
    public static final String DESTINATION = "destination";

    /** The {@code bundle} field: a component's NiFi bundle coordinates. */
    public static final String BUNDLE = "bundle";
    /** The bundle {@code group} coordinate. */
    public static final String GROUP = "group";
    /** The bundle {@code artifact} coordinate. */
    public static final String ARTIFACT = "artifact";
    /** The {@code version} field, including the bundle {@code version} coordinate. */
    public static final String VERSION = "version";

    /** The {@code processors} child collection. */
    public static final String PROCESSORS = "processors";
    /** The {@code controllerServices} child collection. */
    public static final String CONTROLLER_SERVICES = "controllerServices";
    /** The {@code inputPorts} child collection. */
    public static final String INPUT_PORTS = "inputPorts";
    /** The {@code outputPorts} child collection. */
    public static final String OUTPUT_PORTS = "outputPorts";
    /** The {@code funnels} child collection. */
    public static final String FUNNELS = "funnels";
    /** The {@code labels} child collection. */
    public static final String LABELS = "labels";
    /** The {@code connections} child collection. */
    public static final String CONNECTIONS = "connections";
    /** The {@code remoteProcessGroups} child collection. */
    public static final String REMOTE_PROCESS_GROUPS = "remoteProcessGroups";
    /** The {@code processGroups} child collection. */
    public static final String PROCESS_GROUPS = "processGroups";
    /** The {@code contents} field of a remote process group. */
    public static final String CONTENTS = "contents";

    /** The top-level {@code flowContents} section, marking a JSON object as a flow export. */
    public static final String FLOW_CONTENTS = "flowContents";
    /** The top-level {@code flowEncodingVersion} section. */
    public static final String FLOW_ENCODING_VERSION = "flowEncodingVersion";
    /** The top-level {@code parameterContexts} section. */
    public static final String PARAMETER_CONTEXTS = "parameterContexts";
    /** The top-level {@code parameterProviders} section. */
    public static final String PARAMETER_PROVIDERS = "parameterProviders";
    /** The top-level {@code externalControllerServices} section. */
    public static final String EXTERNAL_CONTROLLER_SERVICES = "externalControllerServices";
    /** The top-level {@code snapshotMetadata} section. */
    public static final String SNAPSHOT_METADATA = "snapshotMetadata";
    /** The {@code parameters} array of a parameter context. */
    public static final String PARAMETERS = "parameters";

    private FlowFields() {
    }
}

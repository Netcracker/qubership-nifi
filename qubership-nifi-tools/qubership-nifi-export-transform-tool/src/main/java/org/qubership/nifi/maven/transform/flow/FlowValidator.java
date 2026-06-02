package org.qubership.nifi.maven.transform.flow;

import org.qubership.nifi.maven.transform.config.PluginConfig;
import org.qubership.nifi.maven.transform.exception.ExtractException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the structural integrity of a flow before the Extract operation.
 *
 * Ensures that all target processors (whose types are defined in the config)
 * have unique full paths within the flow.
 */
public class FlowValidator {

    /**
     * Validates that all processors of configured types have unique full paths in the flow.
     * Called only during Extract — not needed during Build.
     *
     * @param flow   flow to validate, must contain a pre-built processorsByType map
     * @param config plugin config defining which processor types to handle
     * @throws ExtractException if two processors of the same configured type
     *                          produce identical file paths
     */
    public void validateNamesUniqueness(FlowFile flow, PluginConfig config)
            throws ExtractException {

        for (var typeConfig : config.getProcessorTypes()) {
            String typeFqn = typeConfig.getProcessorTypeFqn();
            List<Processor> processors = flow.getProcessorsByType(typeFqn);
            checkUniqueProcessorPaths(processors, typeFqn);
        }
    }

    /**
     * Checks that all processors of the given type have unique full paths.
     */
    private void checkUniqueProcessorPaths(List<Processor> processors, String typeFqn)
            throws ExtractException {

        Set<String> seenPaths = new HashSet<>();

        for (Processor processor : processors) {
            List<String> segments = new ArrayList<>(
                    processor.getParentGroup().getPathSegments());
            segments.add(processor.getName());
            String fullPath = String.join(" / ", segments);

            if (!seenPaths.add(fullPath)) {
                throw new ExtractException(String.format(
                        "Duplicate processor path '%s' for type '%s'. " +
                                "Processors of the same type must have unique paths " +
                                "(group segments + processor name) within the flow, " +
                                "because the path is used as the directory structure during Extract.",
                        fullPath, typeFqn));
            }
        }
    }
}

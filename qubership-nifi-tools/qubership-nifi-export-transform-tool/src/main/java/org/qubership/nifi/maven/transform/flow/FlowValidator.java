package org.qubership.nifi.maven.transform.flow;

import org.qubership.nifi.maven.transform.exception.ExtractException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the structural integrity of a flow before the Extract operation.
 *
 * Ensures that processor names and process group names are unique at each level
 * of the hierarchy. This automatically guarantees uniqueness of extracted file paths:
 * flowConf_flow / group / processor / file.sql
 *
 */
public class FlowValidator {

    /**
     * Recursively validates name uniqueness across the entire flow tree.
     * Called only during Extract — not needed during Build.
     *
     * @param flow flow to validate
     * @throws ExtractException if duplicate names are found
     */
    public void validateNamesUniqueness(FlowFile flow) throws ExtractException {
        validateGroup(flow.getRootGroup());
    }


    private void validateGroup(ProcessGroup group) throws ExtractException {
        if (group.isVersioned()) {
            return;
        }
        checkDuplicateProcessorNames(group);
        checkDuplicateGroupNames(group);
        for (ProcessGroup child : group.getChildren()) {
            validateGroup(child);
        }
    }

    private void checkDuplicateProcessorNames(ProcessGroup group) throws ExtractException {
        List<Processor> processors = group.getProcessors();
        Set<String> seen = new HashSet<>();
        for (Processor processor : processors) {
            if (!seen.add(processor.getName())) {
                throw new ExtractException(String.format(
                        "Duplicate processor name '%s' in group '%s'. " +
                                "All processor names within a group must be unique " +
                                "because they are used as directory names during Extract.",
                        processor.getName(), group.getName()));
            }
        }
    }

    private void checkDuplicateGroupNames(ProcessGroup group) throws ExtractException {
        List<ProcessGroup> children = group.getChildren();
        Set<String> seen = new HashSet<>();
        for (ProcessGroup child : children) {
            if (!seen.add(child.getName())) {
                throw new ExtractException(String.format(
                        "Duplicate process group name '%s' inside group '%s'. " +
                                "All group names within a parent group must be unique " +
                                "because they are used as directory names during Extract.",
                        child.getName(), group.getName()));
            }
        }
    }
}

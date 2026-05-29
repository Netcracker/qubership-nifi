package org.qubership.nifi.maven.transform.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively traverses the ProcessGroup tree to find processors.
 * Skips versioned groups — their content is not expanded in the export.
 */
public class FlowScanner {

    /**
     * Finds all processors of the given type in the flow.
     *
     * @param flow    flow to search
     * @param typeFqn fully qualified processor type name.
     * @return list of matching processors, may be empty
     */
    public List<Processor> findProcessorsByType(FlowFile flow, String typeFqn) {
        List<Processor> result = new ArrayList<>();
        collectByType(flow.getRootGroup(), typeFqn, result);
        return result;
    }


    private void collectByType(ProcessGroup group, String typeFqn, List<Processor> result) {
        if (group.isVersioned()) {
            return;
        }
        for (Processor processor : group.getProcessors()) {
            if (typeFqn.equals(processor.getTypeFqn())) {
                result.add(processor);
            }
        }
        for (ProcessGroup child : group.getChildren()) {
            collectByType(child, typeFqn, result);
        }
    }
}

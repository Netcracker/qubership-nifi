package org.qubership.nifi;

import java.util.List;

public class CustomComponentEntity {

    private String componentName;
    private ComponentType type;
    private String componentNar;
    private String componentDescription;
    private List<PropertyDescriptorEntity> componentProperties;

    public CustomComponentEntity(
            String componentName,
            ComponentType type,
            String componentNar,
            String componentDescription,
            List<PropertyDescriptorEntity> componentProperties
    ) {
        this.componentName = componentName;
        this.type = type;
        this.componentNar = componentNar;
        this.componentDescription = componentDescription;
        this.componentProperties = componentProperties;
    }

    public String getComponentName() {
        return componentName;
    }

    public ComponentType getType() {
        return type;
    }

    public String getComponentNar() {
        return componentNar;
    }

    public String getComponentDescription() {
        return componentDescription;
    }

    public List<PropertyDescriptorEntity> getComponentProperties() {
        return componentProperties;
    }
}

package org.qubership.nifi;

import java.util.List;

public class CustomComponentEntity {

    private String componentName;
    private ComponentType type;
    private String componentNar;
    private String componentDescription;
    private List<PropertyDescriptorEntity> componentProperties;

    /**
     * Constructor for class CustomComponentEntity.
     *
     * @param componentNameValue component name value
     * @param typeValue type value
     * @param componentNarValue component nar value
     * @param componentDescriptionValue component description value
     * @param componentPropertiesValue component properties value
     */
    public CustomComponentEntity(
            final String componentNameValue,
            final ComponentType typeValue,
            final String componentNarValue,
            final String componentDescriptionValue,
            final List<PropertyDescriptorEntity> componentPropertiesValue
    ) {
        this.componentName = componentNameValue;
        this.type = typeValue;
        this.componentNar = componentNarValue;
        this.componentDescription = componentDescriptionValue;
        this.componentProperties = componentPropertiesValue;
    }

    /**
     * Gets component name.
     * @return component name
     */
    public String getComponentName() {
        return componentName;
    }

    /**
     * Gets type.
     * @return type
     */
    public ComponentType getType() {
        return type;
    }

    /**
     * Gets component nar.
     * @return component nar
     */
    public String getComponentNar() {
        return componentNar;
    }

    /**
     * Gets component description.
     * @return component description
     */
    public String getComponentDescription() {
        return componentDescription;
    }

    /**
     * Gets component properties.
     * @return component properties
     */
    public List<PropertyDescriptorEntity> getComponentProperties() {
        return componentProperties;
    }
}

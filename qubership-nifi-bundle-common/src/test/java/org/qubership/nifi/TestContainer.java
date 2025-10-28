package org.qubership.nifi;

public class TestContainer {
    private String name;
    private String description;
    private boolean isEnabled;

    public TestContainer() {
        //Default empty constructor.
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        this.name = newName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String newDescription) {
        this.description = newDescription;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
}

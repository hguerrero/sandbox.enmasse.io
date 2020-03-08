package io.enmasse.sandbox.api;

import io.enmasse.sandbox.api.k8s.SandboxTenant;

public class Tenant {
    private String name;
    private String creationTimestamp;
    private String provisionTimestamp;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(String creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public String getProvisionTimestamp() {
        return provisionTimestamp;
    }

    public void setProvisionTimestamp(String provisionTimestamp) {
        this.provisionTimestamp = provisionTimestamp;
    }
}
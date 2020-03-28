package io.enmasse.sandbox.api;

import io.enmasse.sandbox.model.CustomResources;
import io.enmasse.sandbox.model.DoneableSandboxTenant;
import io.enmasse.sandbox.model.SandboxTenant;
import io.enmasse.sandbox.model.SandboxTenantList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Path("/api/tenants")
@Authenticated
public class TenantResource {
    @Inject
    SecurityIdentity identity;

    @Inject
    KubernetesClient kubernetesClient;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void create(Tenant tenant) {
        MixedOperation<SandboxTenant, SandboxTenantList, DoneableSandboxTenant, Resource<SandboxTenant, DoneableSandboxTenant>> op = kubernetesClient.customResources(CustomResources.getSandboxCrd(), SandboxTenant.class, SandboxTenantList.class, DoneableSandboxTenant.class);
        SandboxTenant sandboxTenant = op.withName(tenant.getName()).get();
        if (sandboxTenant != null) {
            throw new WebApplicationException("Tenant already exists", 409);
        }
        op.withName(tenant.getName()).createNew()
                .editOrNewMetadata()
                .withName(tenant.getName())
                .endMetadata()
                .editOrNewSpec()
                .withSubject(tenant.getSubject())
                .endSpec()
                .done();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{name}")
    public Tenant get(@PathParam("name") String name) {
        if (!name.equals(identity.getPrincipal().getName())) {
            throw new UnauthorizedException("Unknown tenant " + name);
        }

        MixedOperation<SandboxTenant, SandboxTenantList, DoneableSandboxTenant, Resource<SandboxTenant, DoneableSandboxTenant>> op = kubernetesClient.customResources(CustomResources.getSandboxCrd(), SandboxTenant.class, SandboxTenantList.class, DoneableSandboxTenant.class);
        List<SandboxTenant> tenants = op.list().getItems();
        SandboxTenant sandboxTenant = tenants.stream()
                .filter(t -> t.getMetadata().getName().equals(name))
                .findAny()
                .orElse(null);
        if (sandboxTenant == null) {
            throw new NotFoundException("Unknown tenant " + name);
        }

        Tenant tenant = new Tenant();
        tenant.setName(sandboxTenant.getMetadata().getName());
        tenant.setSubject(sandboxTenant.getSpec().getSubject());
        tenant.setCreationTimestamp(sandboxTenant.getMetadata().getCreationTimestamp());
        if (sandboxTenant.getStatus() != null) {
            if (sandboxTenant.getStatus().getProvisionTimestamp() != null) {
                tenant.setProvisionTimestamp(sandboxTenant.getStatus().getProvisionTimestamp());
            }
            if (sandboxTenant.getStatus().getExpirationTimestamp() != null) {
                tenant.setExpirationTimestamp(sandboxTenant.getStatus().getExpirationTimestamp());
            }
            if (sandboxTenant.getStatus().getConsoleUrl() != null) {
                tenant.setConsoleUrl(sandboxTenant.getStatus().getConsoleUrl());
            }
            if (sandboxTenant.getStatus().getMessagingUrl() != null) {
                tenant.setMessagingUrl(sandboxTenant.getStatus().getMessagingUrl());
            }
            if (sandboxTenant.getStatus().getNamespace() != null) {
                tenant.setNamespace(sandboxTenant.getStatus().getNamespace());
            }
        } else {
            setEstimates(tenant, tenants);
        }
        return tenant;
    }

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));

    @ConfigProperty(name = "enmasse.sandbox.expiration-time", defaultValue = "3h")
    Duration expirationTime;

    private void setEstimates(Tenant tenant, List<SandboxTenant> tenants) {
        List<SandboxTenant> tenantsByExpiration = tenants.stream()
                .filter(sandboxTenant -> sandboxTenant.getStatus() != null && sandboxTenant.getStatus().getExpirationTimestamp() != null)
                .sorted((a, b) -> {
                    LocalDateTime dateA = LocalDateTime.from(dateTimeFormatter.parse(a.getStatus().getExpirationTimestamp()));
                    LocalDateTime dateB = LocalDateTime.from(dateTimeFormatter.parse(b.getStatus().getExpirationTimestamp()));
                    return dateA.compareTo(dateB);
                }).collect(Collectors.toList());

        // Locate starting point - either now - or the last expiring tenant.
        LocalDateTime start = LocalDateTime.now(ZoneId.of("UTC"));
        int placeInQueue = 1;
        if (!tenantsByExpiration.isEmpty()) {
            SandboxTenant lastExpiringTenant = tenantsByExpiration.get(tenantsByExpiration.size() - 1);
            start = LocalDateTime.from(dateTimeFormatter.parse(lastExpiringTenant.getStatus().getExpirationTimestamp()));
        }

        // Iterate over everone before us in the queue and increment estimate
        List<SandboxTenant> unprovisionedByCreationTime = tenants.stream()
                .filter(sandboxTenant -> sandboxTenant.getStatus() == null || sandboxTenant.getStatus().getProvisionTimestamp() == null)
                .sorted((a, b) -> {
                    LocalDateTime dateA = LocalDateTime.from(dateTimeFormatter.parse(a.getMetadata().getCreationTimestamp()));
                    LocalDateTime dateB = LocalDateTime.from(dateTimeFormatter.parse(b.getMetadata().getCreationTimestamp()));
                    return dateA.compareTo(dateB);
                }).collect(Collectors.toList());

        for (SandboxTenant unprovisioned : unprovisionedByCreationTime) {
            if (unprovisioned.getMetadata().getName().equals(tenant.getName())) {
                break;
            }
            start.plus(expirationTime);
            placeInQueue++;
        }

        tenant.setPlaceInQueue(placeInQueue);
        tenant.setEstimatedProvisionTime(dateTimeFormatter.format(start));
    }

    @DELETE
    @Path("{name}")
    public void delete(@PathParam("name") String name) {
        if (!name.equals(identity.getPrincipal().getName())) {
            throw new UnauthorizedException("Unknown tenant " + name);
        }

        MixedOperation<SandboxTenant, SandboxTenantList, DoneableSandboxTenant, Resource<SandboxTenant, DoneableSandboxTenant>> op = kubernetesClient.customResources(CustomResources.getSandboxCrd(), SandboxTenant.class, SandboxTenantList.class, DoneableSandboxTenant.class);
        SandboxTenant sandboxTenant = op.withName(name).get();
        if (sandboxTenant == null) {
            throw new NotFoundException("Unknown tenant " + name);
        }
        if (!op.withName(name).delete()) {
            throw new InternalServerErrorException("Error deleting tenant");
        }
    }
}

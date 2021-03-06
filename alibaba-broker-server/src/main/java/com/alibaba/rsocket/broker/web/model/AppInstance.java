package com.alibaba.rsocket.broker.web.model;

import com.alibaba.rsocket.ServiceLocator;
import com.alibaba.rsocket.metadata.AppMetadata;

import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * App Instance
 *
 * @author leijuan
 */
public class AppInstance {
    private String id;
    private String name;
    private String ip;
    private Date connectedAt;
    private Integer status;
    private Set<ServiceLocator> services;
    private Set<String> consumedServices;
    private String orgs;
    private String serviceAccounts;
    private String roles;
    private AppMetadata metadata;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Set<ServiceLocator> getServices() {
        return services;
    }

    public void setServices(Set<ServiceLocator> services) {
        this.services = services;
    }

    public Set<String> getConsumedServices() {
        return consumedServices;
    }

    public void setConsumedServices(Set<String> consumedServices) {
        this.consumedServices = consumedServices;
    }

    public Date getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(Date connectedAt) {
        this.connectedAt = connectedAt;
    }

    public String getOrgs() {
        return orgs;
    }

    public void setOrgs(String orgs) {
        this.orgs = orgs;
    }

    public String getServiceAccounts() {
        return serviceAccounts;
    }

    public void setServiceAccounts(String serviceAccounts) {
        this.serviceAccounts = serviceAccounts;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public AppMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(AppMetadata metadata) {
        this.metadata = metadata;
    }

    public String getServicesHTML() {
        if (services == null || services.isEmpty()) {
            return "";
        }
        return services.stream().map(ServiceLocator::toString).collect(Collectors.joining("<br/>"));
    }

    public String getConsumedServicesHTML() {
        if (consumedServices == null || consumedServices.isEmpty()) {
            return "";
        }
        return String.join("<br/>", consumedServices);
    }

}

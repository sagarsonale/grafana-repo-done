package com.automation.grafana.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GrafanaProperties {

    @Value("${source.grafana.url}")
    private String sourceUrl;

    @Value("${source.grafana.token}")
    private String sourceToken;

    @Value("${destination.grafana.url}")
    private String destinationUrl;

    @Value("${destination.grafana.token}")
    private String destinationToken;

    @Value("${source.grafana.datasource.uid}")
    private String sourceDatasourceUid;

    @Value("${destination.grafana.datasource.uid}")
    private String destinationDatasourceUid;

    @Value("${grafana.namespace:default}")
    private String namespace;

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceToken() {
        return sourceToken;
    }

    public String getDestinationUrl() {
        return destinationUrl;
    }

    public String getDestinationToken() {
        return destinationToken;
    }

    public String getSourceDatasourceUid() {
        return sourceDatasourceUid;
    }

    public String getDestinationDatasourceUid() {
        return destinationDatasourceUid;
    }

    public String getNamespace() {
        return namespace;
    }
}
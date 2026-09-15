package com.automation.grafana.service;

import com.automation.grafana.client.GrafanaClient;
import com.automation.grafana.config.GrafanaProperties;
import com.automation.grafana.model.CreateFolderRequest;
import com.automation.grafana.model.Dashboard;
import com.automation.grafana.model.Folder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FolderService {

    private static final Logger log =
            LoggerFactory.getLogger(FolderService.class);

    private static final String GROUP = "dashboard.grafana.app";

    /**
     * Versions we know how to copy spec-for-spec, most preferred first.
     * v2* is deliberately excluded: its spec uses elements/layouts instead of
     * panels/schemaVersion, so a v1 spec posted there migrates from
     * schemaVersion 0 and fails with "dashboard is nil".
     */
    private static final List<String> SUPPORTED_VERSIONS =
            List.of("v1beta1", "v1", "v0alpha1");

    private final GrafanaClient grafanaClient;
    private final GrafanaProperties grafanaProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Negotiated once, then reused for both source reads and destination writes. */
    private String resolvedVersion;

    public FolderService(
            GrafanaClient grafanaClient,
            GrafanaProperties grafanaProperties) {

        this.grafanaClient = grafanaClient;
        this.grafanaProperties = grafanaProperties;
    }

    // =========================================================
    // SOURCE FOLDERS
    // =========================================================

    public List<Folder> getFolders() throws Exception {

        String response = grafanaClient.get(
                grafanaProperties.getSourceUrl(),
                grafanaProperties.getSourceToken(),
                "/api/folders"
        );

        return objectMapper.readValue(
                response,
                new TypeReference<List<Folder>>() {}
        );
    }

    // =========================================================
    // DESTINATION FOLDERS
    // =========================================================

    public List<Folder> getDestinationFolders() throws Exception {

        String response = grafanaClient.get(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken(),
                "/api/folders"
        );

        return objectMapper.readValue(
                response,
                new TypeReference<List<Folder>>() {}
        );
    }

    public boolean folderExists(String folderTitle) throws Exception {

        for (Folder folder : getDestinationFolders()) {

            if (folder.getTitle().equalsIgnoreCase(folderTitle)) {
                return true;
            }
        }

        return false;
    }

    public String createFolder(String title) throws Exception {

        return grafanaClient.post(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken(),
                "/api/folders",
                new CreateFolderRequest(title)
        );
    }

    public String getDestinationFolderUid(
            String folderTitle) throws Exception {

        for (Folder folder : getDestinationFolders()) {

            if (folder.getTitle().equalsIgnoreCase(folderTitle)) {
                return folder.getUid();
            }
        }

        throw new IllegalStateException(
                "Destination folder not found: " + folderTitle
        );
    }

    // =========================================================
    // SOURCE DASHBOARDS
    // =========================================================

    public List<Dashboard> getDashboards() throws Exception {

        String response = grafanaClient.get(
                grafanaProperties.getSourceUrl(),
                grafanaProperties.getSourceToken(),
                "/api/search?type=dash-db&limit=5000"
        );

        return objectMapper.readValue(
                response,
                new TypeReference<List<Dashboard>>() {}
        );
    }

    // =========================================================
    // API VERSION NEGOTIATION
    // =========================================================

    /**
     * Asks both Grafana instances which versions of dashboard.grafana.app they
     * serve and picks the best one they have in common. Hardcoding a version
     * that the server does not serve is what produces an empty decoded spec
     * and the 422 "schema migration from version 0 to 42 failed".
     */
    public String resolveApiVersion() throws Exception {

        if (resolvedVersion != null) {
            return resolvedVersion;
        }

        List<String> sourceVersions = getServedVersions(
                grafanaProperties.getSourceUrl(),
                grafanaProperties.getSourceToken()
        );

        List<String> destinationVersions = getServedVersions(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken()
        );

        log.info("Source serves {}", sourceVersions);
        log.info("Destination serves {}", destinationVersions);

        for (String candidate : SUPPORTED_VERSIONS) {

            if (sourceVersions.contains(candidate)
                    && destinationVersions.contains(candidate)) {

                resolvedVersion = candidate;

                log.info("Using {}/{}", GROUP, resolvedVersion);

                return resolvedVersion;
            }
        }

        throw new IllegalStateException(
                "No common supported version of " + GROUP
                        + ". Source=" + sourceVersions
                        + " Destination=" + destinationVersions
                        + " Supported=" + SUPPORTED_VERSIONS
        );
    }

    private List<String> getServedVersions(
            String url,
            String token) throws Exception {

        String response = grafanaClient.get(
                url,
                token,
                "/apis/" + GROUP
        );

        JsonNode versions = objectMapper
                .readTree(response)
                .path("versions");

        List<String> result = new ArrayList<>();

        for (JsonNode version : versions) {

            String name = version.path("version").asText(null);

            if (name != null && !name.isBlank()) {
                result.add(name);
            }
        }

        return result;
    }

    // =========================================================
    // NAMESPACE
    // =========================================================

    /**
     * "default" is only right for single-org OSS. Multi-org is "org-{id}",
     * Grafana Cloud is "stacks-{id}". Read it from config if you have it set,
     * otherwise fall back to default.
     */
    private String namespace() {

        String configured = grafanaProperties.getNamespace();

        return (configured == null || configured.isBlank())
                ? "default"
                : configured;
    }

    private String dashboardsPath() throws Exception {

        return "/apis/" + GROUP
                + "/" + resolveApiVersion()
                + "/namespaces/" + namespace()
                + "/dashboards";
    }

    // =========================================================
    // SOURCE DASHBOARD - APP PLATFORM API
    // =========================================================

    public JsonNode getSourceDashboardNewApi(
            String uid) throws Exception {

        String response = grafanaClient.get(
                grafanaProperties.getSourceUrl(),
                grafanaProperties.getSourceToken(),
                dashboardsPath() + "/" + uid
        );

        return objectMapper.readTree(response);
    }

    private JsonNode getDestinationDashboardOrNull(
            String name) throws Exception {

        try {

            String response = grafanaClient.get(
                    grafanaProperties.getDestinationUrl(),
                    grafanaProperties.getDestinationToken(),
                    dashboardsPath() + "/" + name
            );

            return objectMapper.readTree(response);

        } catch (HttpClientErrorException.NotFound e) {

            return null;
        }
    }

    // =========================================================
    // DESTINATION DATASOURCES
    // =========================================================

    public String getDestinationDatasources() throws Exception {

        return grafanaClient.get(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken(),
                "/api/datasources"
        );
    }

    // =========================================================
    // IMPORT ONE DASHBOARD
    // =========================================================

    public String importDashboardNewApi(
            String sourceUid,
            String destinationFolderUid) throws Exception {

        String apiVersion = resolveApiVersion();

        // ----- 1. Read source -----

        JsonNode sourceRoot = getSourceDashboardNewApi(sourceUid);

        JsonNode specNode = sourceRoot.get("spec");

        if (specNode == null || !specNode.isObject()) {

            throw new IllegalStateException(
                    "Source dashboard " + sourceUid
                            + " has no usable 'spec'. Got top-level fields: "
                            + fieldNames(sourceRoot)
            );
        }

        ObjectNode spec = (ObjectNode) specNode.deepCopy();

        // ----- 2. Strip fields the destination owns -----
        // Note: schemaVersion must NOT be removed. Without it the server
        // migrates from version 0 and rejects the dashboard.

        spec.remove("id");
        spec.remove("uid");
        spec.remove("version");

        if (!spec.hasNonNull("schemaVersion")
                && !"v2".regionMatches(0, apiVersion, 0, 2)) {

            throw new IllegalStateException(
                    "Source spec for " + sourceUid
                            + " has no schemaVersion; posting it would fail "
                            + "migration. Spec fields: " + fieldNames(spec)
            );
        }

        // ----- 3. Rewrite datasource UIDs -----

        String sourceDatasourceUid =
                grafanaProperties.getSourceDatasourceUid();

        String destinationDatasourceUid =
                grafanaProperties.getDestinationDatasourceUid();

        if (sourceDatasourceUid != null
                && !sourceDatasourceUid.isBlank()
                && destinationDatasourceUid != null
                && !destinationDatasourceUid.isBlank()) {

            int replaced = replaceDatasourceUid(
                    spec,
                    sourceDatasourceUid,
                    destinationDatasourceUid
            );

            log.info(
                    "Rewrote {} datasource reference(s) {} -> {}",
                    replaced,
                    sourceDatasourceUid,
                    destinationDatasourceUid
            );
        }

        // ----- 4. Build metadata -----
        // metadata.name is the resource name and must be a valid k8s name:
        // lowercase alphanumeric, '-' or '.', starting and ending alphanumeric.
        // Legacy Grafana UIDs can contain uppercase and underscores.

        String name = sanitizeResourceName(sourceUid);

        ObjectNode metadata = objectMapper.createObjectNode();

        metadata.put("name", name);

        ObjectNode annotations = objectMapper.createObjectNode();

        annotations.put("grafana.app/folder", destinationFolderUid);

        metadata.set("annotations", annotations);

        // ----- 5. Build the resource -----
        // Body apiVersion must match the version in the request path.

        ObjectNode request = objectMapper.createObjectNode();

        request.put("apiVersion", GROUP + "/" + apiVersion);
        request.put("kind", "Dashboard");
        request.set("metadata", metadata);
        request.set("spec", spec);

        if (log.isDebugEnabled()) {

            log.debug(
                    "Dashboard payload:\n{}",
                    objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(request)
            );
        }

        // ----- 6. Create, or update if it already exists -----
        // There is no "overwrite" flag on this API. Updating requires the
        // current resourceVersion or the server returns 409 Conflict.

        JsonNode existing = getDestinationDashboardOrNull(name);

        if (existing == null) {

            log.info("Creating dashboard {}", name);

            return grafanaClient.post(
                    grafanaProperties.getDestinationUrl(),
                    grafanaProperties.getDestinationToken(),
                    dashboardsPath(),
                    request
            );
        }

        String resourceVersion = existing
                .path("metadata")
                .path("resourceVersion")
                .asText(null);

        if (resourceVersion == null || resourceVersion.isBlank()) {

            throw new IllegalStateException(
                    "Existing dashboard " + name
                            + " has no metadata.resourceVersion; cannot update."
            );
        }

        metadata.put("resourceVersion", resourceVersion);

        log.info(
                "Updating dashboard {} (resourceVersion {})",
                name,
                resourceVersion
        );

        return grafanaClient.put(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken(),
                dashboardsPath() + "/" + name,
                request
        );
    }

    // =========================================================
    // HELPERS
    // =========================================================

    static String sanitizeResourceName(String uid) {

        if (uid == null || uid.isBlank()) {

            throw new IllegalArgumentException(
                    "Dashboard uid is null or blank"
            );
        }

        String name = uid
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("^[^a-z0-9]+", "")
                .replaceAll("[^a-z0-9]+$", "");

        if (name.length() > 253) {
            name = name.substring(0, 253)
                    .replaceAll("[^a-z0-9]+$", "");
        }

        if (name.isEmpty()) {

            throw new IllegalArgumentException(
                    "Dashboard uid '" + uid
                            + "' cannot be converted to a valid resource name"
            );
        }

        return name;
    }

    private static String fieldNames(JsonNode node) {

        List<String> names = new ArrayList<>();

        node.fieldNames().forEachRemaining(names::add);

        return names.toString();
    }

    /**
     * Rewrites every textual value equal to sourceUid. Field names are
     * collected before mutating so we never write to the node we are
     * iterating. Returns how many values were changed, which is worth
     * asserting on: zero usually means the hardcoded source uid is wrong.
     */
    private int replaceDatasourceUid(
            JsonNode node,
            String sourceUid,
            String destinationUid) {

        int count = 0;

        if (node.isObject()) {

            ObjectNode objectNode = (ObjectNode) node;

            List<String> keys = new ArrayList<>();

            objectNode.fieldNames().forEachRemaining(keys::add);

            for (String key : keys) {

                JsonNode value = objectNode.get(key);

                if (value.isTextual()
                        && sourceUid.equals(value.asText())) {

                    objectNode.put(key, destinationUid);

                    count++;

                } else {

                    count += replaceDatasourceUid(
                            value,
                            sourceUid,
                            destinationUid
                    );
                }
            }

        } else if (node.isArray()) {

            for (JsonNode child : node) {

                count += replaceDatasourceUid(
                        child,
                        sourceUid,
                        destinationUid
                );
            }
        }

        return count;
    }

    // =========================================================
    // DESTINATION DASHBOARDS
    // =========================================================

    public String getDestinationDashboardNewApi() throws Exception {

        return grafanaClient.get(
                grafanaProperties.getDestinationUrl(),
                grafanaProperties.getDestinationToken(),
                dashboardsPath()
        );
    }
}
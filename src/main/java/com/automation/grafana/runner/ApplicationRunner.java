package com.automation.grafana.runner;

import com.automation.grafana.model.Dashboard;
import com.automation.grafana.model.Folder;
import com.automation.grafana.service.FolderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApplicationRunner implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ApplicationRunner.class);

    /** Set to true to migrate only the first dashboard, for testing. */
    private static final boolean DRY_RUN_FIRST_ONLY = false;

    private final FolderService folderService;

    public ApplicationRunner(FolderService folderService) {
        this.folderService = folderService;
    }

    @Override
    public void run(String... args) throws Exception {

        String folderToMigrate = "Staging_Master";

        // ----- Negotiate the API version up front -----
        // Fails fast with a clear message instead of a 422 mid-migration.

        folderService.resolveApiVersion();

        // ----- 1. Source folder -----

        List<Folder> folders = folderService.getFolders();

        Folder selectedFolder = folders.stream()
                .filter(folder -> folder.getTitle()
                        .equalsIgnoreCase(folderToMigrate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Source folder not found: " + folderToMigrate
                ));

        log.info(
                "Source folder: {} ({})",
                selectedFolder.getTitle(),
                selectedFolder.getUid()
        );

        // ----- 2. Destination folder -----

        if (folderService.folderExists(selectedFolder.getTitle())) {

            log.info(
                    "Destination folder already exists: {}",
                    selectedFolder.getTitle()
            );

        } else {

            log.info(
                    "Creating destination folder: {}",
                    selectedFolder.getTitle()
            );

            folderService.createFolder(selectedFolder.getTitle());
        }

        String destinationFolderUid =
                folderService.getDestinationFolderUid(
                        selectedFolder.getTitle()
                );

        log.info("Destination folder uid: {}", destinationFolderUid);

        // ----- 3. Dashboards in that folder -----

        List<Dashboard> dashboards = folderService.getDashboards()
                .stream()
                .filter(dashboard -> selectedFolder.getUid()
                        .equals(dashboard.getFolderUid()))
                .toList();

        log.info(
                "Found {} dashboard(s) in {}",
                dashboards.size(),
                selectedFolder.getTitle()
        );

        if (dashboards.isEmpty()) {
            return;
        }

        if (DRY_RUN_FIRST_ONLY) {
            dashboards = dashboards.subList(0, 1);
            log.info("DRY_RUN_FIRST_ONLY is on, migrating one dashboard");
        }

        // ----- 4. Migrate -----
        // One bad dashboard should not abort the whole run.

        int succeeded = 0;
        int failed = 0;

        for (Dashboard dashboard : dashboards) {

            try {

                folderService.importDashboardNewApi(
                        dashboard.getUid(),
                        destinationFolderUid
                );

                succeeded++;

                log.info(
                        "OK   {} ({})",
                        dashboard.getTitle(),
                        dashboard.getUid()
                );

            } catch (Exception e) {

                failed++;

                log.error(
                        "FAIL {} ({}): {}",
                        dashboard.getTitle(),
                        dashboard.getUid(),
                        e.getMessage()
                );
            }
        }

        log.info("Done. {} succeeded, {} failed.", succeeded, failed);

        if (failed > 0) {
            throw new IllegalStateException(
                    failed + " dashboard(s) failed to migrate"
            );
        }
    }
}
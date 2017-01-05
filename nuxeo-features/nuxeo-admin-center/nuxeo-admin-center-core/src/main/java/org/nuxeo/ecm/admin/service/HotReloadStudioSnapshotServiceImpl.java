package org.nuxeo.ecm.admin.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.ExceptionUtils;
import org.nuxeo.connect.connector.ConnectServerError;
import org.nuxeo.connect.data.DownloadingPackage;
import org.nuxeo.connect.packages.PackageManager;
import org.nuxeo.connect.packages.dependencies.DependencyResolution;
import org.nuxeo.connect.packages.dependencies.TargetPlatformFilterHelper;
import org.nuxeo.connect.update.LocalPackage;
import org.nuxeo.connect.update.PackageDependency;
import org.nuxeo.connect.update.PackageException;
import org.nuxeo.connect.update.PackageState;
import org.nuxeo.connect.update.PackageUpdateService;
import org.nuxeo.connect.update.ValidationStatus;
import org.nuxeo.connect.update.task.Task;
import org.nuxeo.ecm.admin.runtime.PlatformVersionHelper;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

public class HotReloadStudioSnapshotServiceImpl extends DefaultComponent implements HotReloadStudioSnapshotService {

    /**
     * Component activated notification. Called when the component is activated. All component dependencies are resolved
     * at that moment. Use this method to initialize the component.
     *
     * @param context the component context.
     */
    @Override
    public void activate(ComponentContext context) {
        super.activate(context);
    }

    /**
     * Component deactivated notification. Called before a component is unregistered. Use this method to do cleanup if
     * any and free any resources held by the component.
     *
     * @param context the component context.
     */
    @Override
    public void deactivate(ComponentContext context) {
        super.deactivate(context);
    }

    /**
     * Application started notification. Called after the application started. You can do here any initialization that
     * requires a working application (all resolved bundles and components are active at that moment)
     *
     * @param context the component context. Use it to get the current bundle context
     * @throws Exception
     */
    @Override
    public void applicationStarted(ComponentContext context) {
        // do nothing by default. You can remove this method if not used.
    }

    @Override
    public void registerContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        // Add some logic here to handle contributions
    }

    @Override
    public void unregisterContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        // Logic to do when unregistering any contribution
    }

    protected static final Log log = LogFactory.getLog(HotReloadStudioSnapshotService.class);

    protected static void removePackage(PackageUpdateService pus, LocalPackage pkg) throws PackageException {
        log.info(String.format("Removing package %s before update...", pkg.getId()));
        if (pkg.getPackageState().isInstalled()) {
            // First remove it to allow SNAPSHOT upgrade
            log.info("Uninstalling " + pkg.getId());
            Task uninstallTask = pkg.getUninstallTask();
            try {
                performTask(uninstallTask);
            } catch (PackageException e) {
                uninstallTask.rollback();
                throw e;
            }
        }
        pus.removePackage(pkg.getId());
    }

    protected static void performTask(Task task) throws PackageException {
        ValidationStatus validationStatus = task.validate();
        if (validationStatus.hasErrors()) {
            throw new PackageException(
                    "Failed to validate package " + task.getPackage().getId() + " -> " + validationStatus.getErrors());
        }
        if (validationStatus.hasWarnings()) {
            log.warn("Got warnings on package validation " + task.getPackage().getId() + " -> "
                    + validationStatus.getWarnings());
        }
        task.run(null);
    }

    public void hotReload(PackageManager pm, String packageId, boolean validate) {

        PackageUpdateService pus = Framework.getLocalService(PackageUpdateService.class);
        LocalPackage pkg;
        try {
            pkg = pus.getPackage(packageId);
        } catch (PackageException e) {
            throw new NuxeoException("Cannot perform validation: remote package not found", e);
        }

        if (validate) {

            ValidationStatus status = new ValidationStatus();
            pm.flushCache();

            List<String> pkgUpgrade = new ArrayList<String>();
            pkgUpgrade.add(packageId);

            PackageDependency[] pkgDeps = pkg.getDependencies();
            if (log.isDebugEnabled()) {
                log.debug(String.format("%s target platforms: %s", pkg, ArrayUtils.toString(pkg.getTargetPlatforms())));
                log.debug(String.format("%s dependencies: %s", pkg, ArrayUtils.toString(pkgDeps)));
            }

            String targetPlatform = PlatformVersionHelper.getPlatformFilter();
            if (!TargetPlatformFilterHelper.isCompatibleWithTargetPlatform(pkg, targetPlatform)) {
                throw new NuxeoException(
                        String.format("This package is not validated for your current platform: %s", targetPlatform));
            }

            if (pkgDeps != null && pkgDeps.length > 0) {
                DependencyResolution resolution = pm.resolveDependencies(null, null, pkgUpgrade, targetPlatform);
                if (resolution.isFailed() && targetPlatform != null) {
                    // retry without PF filter in case it gives more information
                    resolution = pm.resolveDependencies(null, null, pkgUpgrade, null);
                }

                if (resolution.isFailed()) {
                    status.addError(
                            String.format("Dependency check has failed for package '%s' (%s)", packageId, resolution));
                } else {
                    List<String> pkgToInstall = resolution.getInstallPackageIds();
                    if (pkgToInstall != null && pkgToInstall.size() == 1 && packageId.equals(pkgToInstall.get(0))) {
                        // ignore
                    } else if (resolution.requireChanges()) {
                        // do not install needed deps: they may not be hot-reloadable and that's not what the
                        // "update snapshot" button is for.
                        status.addError(resolution.toString().trim().replaceAll("\n", "<br />"));
                    }
                }
            }
        }

        try {

            // Uninstall and/or remove if needed
            if (pkg != null) {
                log.info(String.format("Removing package %s before update...", pkg));
                if (pkg.getPackageState().isInstalled()) {
                    // First remove it to allow SNAPSHOT upgrade
                    log.info("Uninstalling " + packageId);
                    removePackage(pus, pkg);
                }
                pus.removePackage(packageId);
            }

            // Download
            DownloadingPackage downloadingPkg = pm.download(packageId);
            while (!downloadingPkg.isCompleted()) {
                log.debug("downloading studio snapshot package " + packageId);
                Thread.sleep(100);
            }

            // Install
            log.info("Installing " + packageId);
            pkg = pus.getPackage(packageId);
            if (pkg == null || PackageState.DOWNLOADED != pkg.getPackageState()) {
                log.error("Error while downloading studio snapshot " + pkg);
                return;
            }
            Task installTask = pkg.getInstallTask();
            try {
                performTask(installTask);
            } catch (PackageException e) {
                installTask.rollback();
                throw e;
            }
        } catch (InterruptedException e) {
            ExceptionUtils.checkInterrupt(e);
            throw new NuxeoException("Error while downloading studio snapshot", e);
        } catch (PackageException | ConnectServerError e) {
            throw new NuxeoException("Error while installing studio snapshot", e);
        }

    }
}

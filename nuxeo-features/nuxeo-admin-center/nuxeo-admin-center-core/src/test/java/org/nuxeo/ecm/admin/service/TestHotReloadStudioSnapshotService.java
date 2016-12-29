package org.nuxeo.ecm.admin.service;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.admin.service.HotReloadStudioSnapshotService;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features({ PlatformFeature.class })
@Deploy("org.nuxeo.admin.center")
public class TestHotReloadStudioSnapshotService {

    @Inject
    protected HotReloadStudioSnapshotService hotReloadStudioSnapshotService;

    @Test
    public void testService() {
        assertNotNull(hotReloadStudioSnapshotService);
    }
}

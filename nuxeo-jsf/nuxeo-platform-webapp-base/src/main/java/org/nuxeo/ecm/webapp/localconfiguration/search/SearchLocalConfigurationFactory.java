/*
 * (C) Copyright 2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     eugen
 */
package org.nuxeo.ecm.webapp.localconfiguration.search;

import static org.nuxeo.ecm.webapp.localconfiguration.search.SearchLocalConfigurationConstants.*;

import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.adapter.DocumentAdapterFactory;

/**
 * @author <a href="mailto:ei@nuxeo.com">Eugen Ionica</a>
 */
public class SearchLocalConfigurationFactory implements DocumentAdapterFactory {

    public Object getAdapter(DocumentModel doc, Class<?> itf) {
        if (doc.hasFacet(SEARCH_LOCAL_CONFIGURATION_FACET)) {
            return new SearchLocalConfigurationAdapter(doc);
        }
        return null;
    }

}

/*
 * (C) Copyright 2014-2016 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Maxime Hilaire
 *     Florent Guillaume
 */
package org.nuxeo.ecm.directory.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.directory.BaseSession;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.PasswordHelper;
import org.nuxeo.runtime.api.Framework;

/**
 * Implementation of a {@link org.nuxeo.ecm.directory.Session Session} for a {@link CoreDirectory}.
 * <p>
 * Directory entries are stored as children documents of the directory folder, itself a child of a single directories
 * root folder.
 * <p>
 * The directory folder is a folderish document whose name is the directory name. Its document type is specified in the
 * directory configuration.
 * <p>
 * A directory entry is a document with the schema specified in the directory configuration. The entry id is stored in
 * the document name.
 * <p>
 * A core schema can be specified for storage instead of the directory schema; this is used for schemas having an "id"
 * field which is forbidden for core VCS storage.
 *
 * @since 8.2
 */
public class CoreDirectorySession extends BaseSession {

    protected String docType;

    protected String idField;

    protected String idFieldPrefixed;

    /** Schema of the directory entries for the {@link org.nuxeo.ecm.directory.Session Session} API. */
    protected String schemaName;

    /** Schema for the storage of the entries in the core. */
    protected String coreSchemaName;

    protected String dirPath;

    public CoreDirectorySession(CoreDirectory directory) {
        super(directory);
        // TODO make this configurable from schema + docType map
        CoreDirectoryDescriptor descriptor = directory.getDescriptor();
        docType = descriptor.docType;
        idField = descriptor.idField;
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        schemaName = descriptor.schemaName;
        coreSchemaName = descriptor.coreSchemaName == null ? schemaName : descriptor.coreSchemaName;
        Field field = schemaManager.getSchema(schemaName).getField(idField);
        if (field == null) {
            throw new DirectoryException(
                    "Unknown field: " + idField + " in schema: " + schemaName + " for directory: " + descriptor.name);
        }
        idFieldPrefixed = field.getName().getPrefixedName();
        if (!schemaManager.getDocumentType(docType).hasSchema(coreSchemaName)) {
            throw new DirectoryException("Unknown schema: " + coreSchemaName + " in doctype: " + docType
                    + " for directory: " + descriptor.name);
        }
    }

    @Override
    public CoreDirectory getDirectory() {
        return (CoreDirectory) directory;
    }

    @Override
    public DocumentModel getEntry(String id) throws DirectoryException {
        return getEntry(id, false);
    }

    @Override
    public DocumentModel getEntry(String id, boolean fetchReferences) throws DirectoryException {
        if (!hasPermission(SecurityConstants.READ)) {
            return null;
        }
        return CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            PathRef pathRef = new PathRef(getDirectory().directoryPath + '/' + id);
            if (!session.exists(pathRef)) {
                return null;
            }
            DocumentModel doc = session.getDocument(pathRef);
            return docToEntry(id, doc);
        });
    }

    /** Maps a core document to a directory entry document. */
    protected DocumentModel docToEntry(String id, DocumentModel doc) {
        Map<String, Object> properties = doc.getProperties(coreSchemaName);
        properties.put(idField, id);
        // TODO : deal with references
        DocumentModel entry = createEntryModel(null, schemaName, id, properties);
        if (isReadOnly()) {
            setReadOnlyEntry(entry);
        }
        return entry;
    }

    @Override
    public DocumentModelList getEntries() throws DirectoryException {
        if (!hasPermission(SecurityConstants.READ)) {
            return new DocumentModelListImpl(0);
        }
        return CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            DocumentModelList docs = session.getChildren(new PathRef(getDirectory().directoryPath));
            DocumentModelList entries = new DocumentModelListImpl(docs.size());
            for (DocumentModel doc : docs) {
                entries.add(docToEntry(doc.getName(), doc));
            }
            return entries;
        });
    }

    @Override
    public DocumentModel createEntry(Map<String, Object> map) throws DirectoryException {
        checkPermission(SecurityConstants.WRITE);
        String id = (String) map.get(idFieldPrefixed);
        if (id == null) {
            throw new DirectoryException("Missing id field for entry: " + map);
        }

        // TODO : deal with encrypted password
        Map<String, Object> properties = new HashMap<String, Object>();
        List<String> createdRefs = new LinkedList<String>();
        for (Entry<String, Object> es : map.entrySet()) {
            String fieldId = es.getKey();
            if (idFieldPrefixed.equals(fieldId)) {
                continue;
            }
            Object value = es.getValue();
            if (getDirectory().isReference(fieldId)) {
                createdRefs.add(fieldId);
            }
            properties.put(fieldId, value);
        }
        return CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            PathRef pathRef = new PathRef(getDirectory().directoryPath + '/' + id);
            if (session.exists(pathRef)) {
                throw new DirectoryException(String.format("Entry with id %s already exists", id));
            }
            DocumentModel doc = session.createDocumentModel(getDirectory().directoryPath, id, docType);
            doc.setProperties(coreSchemaName, properties);
            doc = session.createDocument(doc);
            session.save();
            return docToEntry(id, doc);
        });
    }

    @Override
    public void updateEntry(DocumentModel update) throws DirectoryException {
        checkPermission(SecurityConstants.WRITE);
        String id = (String) update.getPropertyValue(idFieldPrefixed);
        if (id == null) {
            throw new DirectoryException("Cannot update entry with null id: " + update.getProperties(schemaName));
        }
        CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            PathRef pathRef = new PathRef(getDirectory().directoryPath + '/' + id);
            if (!session.exists(pathRef)) {
                throw new DirectoryException("Missing entry with id: " + id);
            }
            DocumentModel doc = session.getDocument(pathRef);
            List<String> updatedRefs = new ArrayList<String>();
            for (Entry<String, Object> es : update.getProperties(schemaName).entrySet()) {
                // TODO reference
                String key = es.getKey();
                if (idFieldPrefixed.equals(key)) {
                    continue;
                }
                if (getDirectory().isReference(key)) {
                    updatedRefs.add(key);
                } else {
                    doc.setPropertyValue(key, (Serializable) es.getValue());
                }
            }
            // TODO update reference fields
            // for (String referenceFieldName : updatedRefs) {
            // Reference reference = directory.getReference(referenceFieldName);
            // List<String> targetIds = (List<String>) update.getProperty(schemaName, referenceFieldName);
            // reference.setTargetIdsForSource(update.getId(), targetIds);
            // }
            session.saveDocument(doc);
            session.save();
        });
    }

    @Override
    public void deleteEntry(DocumentModel doc) throws DirectoryException {
        String id = (String) doc.getPropertyValue(idFieldPrefixed);
        deleteEntry(id);
    }

    @Override
    public void deleteEntry(String id) throws DirectoryException {
        checkPermission(SecurityConstants.WRITE);
        checkDeleteConstraints(id);
        if (id == null) {
            throw new DirectoryException("Cannot delete entry with null id");
        }
        CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            PathRef pathRef = new PathRef(getDirectory().directoryPath + '/' + id);
            if (!session.exists(pathRef)) {
                return;
            }
            // TODO first remove references to this entry
            session.removeDocument(pathRef);
            session.save();
        });
    }

    @Override
    public void deleteEntry(String id, Map<String, String> map) throws DirectoryException {
        throw new UnsupportedOperationException();
    }

    @Override
    public DocumentModelList query(Map<String, Serializable> filter) {
        return query(filter, Collections.emptySet());
    }

    @Override
    public DocumentModelList query(Map<String, Serializable> filter, Set<String> fulltext,
            Map<String, String> orderBy) {
        // XXX not fetch references by default: breaks current behavior
        return query(filter, fulltext, orderBy, false);
    }

    @Override
    public DocumentModelList query(Map<String, Serializable> filter, Set<String> fulltext, Map<String, String> orderBy,
            boolean fetchReferences) {
        return query(filter, fulltext, orderBy, fetchReferences, 0, 0);
    }

    @Override
    public DocumentModelList query(Map<String, Serializable> filter, Set<String> fulltext) throws DirectoryException {
        return query(filter, fulltext, new HashMap<String, String>());
    }

    @Override
    public DocumentModelList query(Map<String, Serializable> filter, Set<String> fulltext, Map<String, String> orderBy,
            boolean fetchReferences, int limit, int offset) throws DirectoryException {
        if (!hasPermission(SecurityConstants.READ)) {
            return new DocumentModelListImpl();
        }
        // TODO deal with fetch ref
        // TODO descriptor's queryLimitSize
        StringBuilder query = new StringBuilder("SELECT * FROM ");
        query.append(docType);
        query.append(" WHERE ");
        addClauses(query, filter, fulltext);
        return CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            DocumentModelList docs = session.query(query.toString(), null, limit, offset, false);
            DocumentModelList entries = new DocumentModelListImpl(docs.size());
            for (DocumentModel doc : docs) {
                entries.add(docToEntry(doc.getName(), doc));
            }
            return entries;
        });
    }

    @Override
    public void close() throws DirectoryException {
        getDirectory().removeSession(this);
    }

    @Override
    public List<String> getProjection(Map<String, Serializable> filter, String columnName) throws DirectoryException {
        return getProjection(filter, Collections.emptySet(), columnName);
    }

    @Override
    public List<String> getProjection(Map<String, Serializable> filter, Set<String> fulltext, String columnName)
            throws DirectoryException {
        if (!hasPermission(SecurityConstants.READ)) {
            return Collections.emptyList();
        }
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        Schema schema = schemaManager.getSchema(schemaName);
        String nxqlCol = nxqlColumn(schema.getField(columnName).getName().getPrefixedName());

        // TODO descriptor's queryLimitSize
        StringBuilder query = new StringBuilder();
        query.append("SELECT ");
        query.append(nxqlCol);
        query.append(" FROM ");
        query.append(docType);
        query.append(" WHERE ");
        addClauses(query, filter, fulltext);
        return CoreInstance.doPrivileged(getDirectory().repositoryName, session -> {
            List<String> results = new ArrayList<>();
            try (IterableQueryResult it = session.queryAndFetch(query.toString(), NXQL.NXQL)) {
                for (Map<String, Serializable> map : it) {
                    results.add((String) map.get(nxqlCol));
                }
            }
            return results;
        });
    }

    /** Finds the NXQL column name to use for the given property. */
    protected String nxqlColumn(String prop) {
        if (idFieldPrefixed.equals(prop)) {
            return NXQL.ECM_NAME;
        } else if (prop.contains(":")) {
            return prop;
        } else {
            // for NXQL we need a fully-qualified column name as there may be ambiguities in schemas that don't have a
            // prefix (ex: vocabulary:label vs xvocabulary:label).
            return coreSchemaName + ":" + prop;
        }
    }

    protected void addClauses(StringBuilder sb, Map<String, Serializable> filter, Set<String> fulltext) {
        List<String> clauses = new ArrayList<>();
        clauses.add("ecm:parentId = '" + getDirectory().directoryFolderId + "'");
        clauses.add("ecm:isProxy = 0");
        clauses.add("ecm:isVersion = 0");
        // clauses.add("ecm:currentLifeCycleState != 'deleted'");

        // TODO deal with fetch ref

        for (Entry<String, Serializable> es : filter.entrySet()) {
            String key = es.getKey();
            if (fulltext.contains(key)) {
                continue;
            }
            String clause = nxqlColumn(key) + " = '" + NXQL.escapeStringInner((String) es.getValue()) + "'";
            clauses.add(clause);
        }
        if (!fulltext.isEmpty()) {
            StringBuilder ft = new StringBuilder();
            for (String key : fulltext) {
                ft.append(filter.get(key));
                ft.append(" ");
            }
            if (ft.length() > 0) {
                ft.setLength(ft.length() - 1);
                String clause = NXQL.ECM_FULLTEXT + " = '" + NXQL.escapeStringInner(ft.toString()) + "'";
                clauses.add(clause);
            }
        }
        for (Iterator<String> it = clauses.iterator(); it.hasNext();) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append(" AND ");
            }
        }
    }

    @Override
    public boolean authenticate(String username, String password) {
        DocumentModel entry = getEntry(username);
        if (entry == null) {
            return false;
        }
        String storedPassword = (String) entry.getProperty(schemaName, directory.getPasswordField());
        return PasswordHelper.verifyPassword(password, storedPassword);
    }

    @Override
    public boolean isAuthenticating() {
        return directory.getPasswordField() != null;
    }

    @Override
    public boolean hasEntry(String id) {
        return getEntry(id) != null;
    }

    @Override
    public DocumentModel createEntry(DocumentModel entry) {
        Map<String, Object> fieldMap = entry.getProperties(schemaName);
        return createEntry(fieldMap);
    }

}

/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.dashboard.server;

import event.logging.BaseAdvancedQueryItem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stroom.dashboard.shared.FindQueryCriteria;
import stroom.dashboard.shared.QueryEntity;
import stroom.dashboard.shared.QueryService;
import stroom.entity.server.AutoMarshal;
import stroom.entity.server.CriteriaLoggingUtil;
import stroom.entity.server.DocumentEntityServiceImpl;
import stroom.entity.server.QueryAppender;
import stroom.entity.server.util.SQLBuilder;
import stroom.entity.server.util.SQLUtil;
import stroom.entity.server.util.StroomEntityManager;
import stroom.entity.shared.EntityServiceException;
import stroom.importexport.server.ImportExportHelper;
import stroom.query.api.v1.DocRef;
import stroom.security.SecurityContext;
import stroom.util.spring.StroomSpringProfiles;

import javax.inject.Inject;
import java.util.List;
import java.util.UUID;

@Profile(StroomSpringProfiles.PROD)
@Component("queryService")
@Transactional
@AutoMarshal
public class QueryServiceImpl extends DocumentEntityServiceImpl<QueryEntity, FindQueryCriteria> implements QueryService {
    private final SecurityContext securityContext;

    @Inject
    QueryServiceImpl(final StroomEntityManager entityManager, final ImportExportHelper importExportHelper, final SecurityContext securityContext) {
        super(entityManager, importExportHelper, securityContext);
        this.securityContext = securityContext;
    }

    @Override
    public Class<QueryEntity> getEntityClass() {
        return QueryEntity.class;
    }

    @Override
    public FindQueryCriteria createCriteria() {
        return new FindQueryCriteria();
    }

    // TODO : Remove this when document entities no longer reference a folder.
    // Don't do any create permission checking as a query doesn't live in a folder and all users are allowed to create queries.
    @Override
    public QueryEntity create(final DocRef folder, final String name) throws RuntimeException {
        // Create a new entity instance.
        QueryEntity entity;
        try {
            entity = getEntityClass().newInstance();
        } catch (final IllegalAccessException | InstantiationException e) {
            throw new EntityServiceException(e.getMessage());
        }

        entity.setName(name);
        if (entity.getUuid() == null) {
            entity.setUuid(UUID.randomUUID().toString());
        }
        entity = super.create(entity);

        // Create the initial user permissions for this new document.
        securityContext.addDocumentPermissions(null, null, entity.getType(), entity.getUuid(), true);

        return entity;
    }

    @Override
    protected void checkUpdatePermission(final QueryEntity entity) {
        // Ignore.
    }

    @Override
    public void appendCriteria(final List<BaseAdvancedQueryItem> items, final FindQueryCriteria criteria) {
        CriteriaLoggingUtil.appendEntityIdSet(items, "dashboardIdSet", criteria.getDashboardIdSet());
        super.appendCriteria(items, criteria);
    }

    @Override
    protected QueryAppender<QueryEntity, FindQueryCriteria> createQueryAppender(final StroomEntityManager entityManager) {
        return new QueryQueryAppender(entityManager);
    }

    @Override
    public String getNamePattern() {
        // Unnamed queries are valid.
        return null;
    }

    private static class QueryQueryAppender extends QueryAppender<QueryEntity, FindQueryCriteria> {
        public QueryQueryAppender(final StroomEntityManager entityManager) {
            super(entityManager);
        }

        @Override
        protected void appendBasicCriteria(final SQLBuilder sql, final String alias, final FindQueryCriteria criteria) {
            super.appendBasicCriteria(sql, alias, criteria);

            if (criteria.getFavourite() != null) {
                SQLUtil.appendValueQuery(sql, alias + ".favourite", criteria.getFavourite());
            }

            SQLUtil.appendSetQuery(sql, true, alias + ".dashboard", criteria.getDashboardIdSet());
        }
    }
}

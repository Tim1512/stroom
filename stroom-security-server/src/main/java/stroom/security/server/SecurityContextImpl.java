/*
 * Copyright 2017 Crown Copyright
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
 */

package stroom.security.server;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.UnavailableSecurityManagerException;
import org.apache.shiro.session.InvalidSessionException;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import stroom.entity.server.GenericEntityService;
import stroom.entity.shared.DocumentEntityService;
import stroom.entity.shared.EntityService;
import stroom.query.api.DocRef;
import stroom.security.SecurityContext;
import stroom.security.server.exception.AuthenticationServiceException;
import stroom.security.shared.DocumentPermissionNames;
import stroom.security.shared.DocumentPermissions;
import stroom.security.shared.PermissionNames;
import stroom.security.shared.User;
import stroom.security.shared.UserRef;
import stroom.security.shared.UserService;
import stroom.security.spring.SecurityConfiguration;
import stroom.util.spring.StroomScope;

import javax.inject.Inject;
import javax.persistence.RollbackException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Set;

@Component
@Profile(SecurityConfiguration.PROD_SECURITY)
@Scope(value = StroomScope.PROTOTYPE, proxyMode = ScopedProxyMode.INTERFACES)
class SecurityContextImpl implements SecurityContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityContextImpl.class);
    private static final UserRef INTERNAL_PROCESSING_USER = new UserRef(User.ENTITY_TYPE, "0", "INTERNAL_PROCESSING_USER", false, true);
    private final UserPermissionsCache userPermissionCache;
    private final DocumentPermissionsCache documentPermissionsCache;
    private final UserService userService;
    private final DocumentPermissionService documentPermissionService;
    private final GenericEntityService genericEntityService;

    @Inject
    SecurityContextImpl(final UserPermissionsCache userPermissionCache, final DocumentPermissionsCache documentPermissionsCache, final UserService userService, final DocumentPermissionService documentPermissionService, final GenericEntityService genericEntityService) {
        this.userPermissionCache = userPermissionCache;
        this.documentPermissionsCache = documentPermissionsCache;
        this.userService = userService;
        this.documentPermissionService = documentPermissionService;
        this.genericEntityService = genericEntityService;
    }

    @Override
    public void pushUser(final String name) {
        UserRef userRef = INTERNAL_PROCESSING_USER;
        if (name != null && !"INTERNAL_PROCESSING_USER".equals(name)) {
            userRef = userService.getUserRefByName(name);
        }

        if (userRef == null) {
            final String message = "Unable to push user '" + name + "' as user is unknown";
            LOGGER.error(message);
            throw new AuthenticationServiceException(message);
        }

        CurrentUserState.pushUserRef(userRef);
    }

    @Override
    public String popUser() {
        final UserRef userRef = CurrentUserState.popUserRef();

        if (userRef != null) {
            return userRef.getName();
        }

        return null;
    }

    private Subject getSubject() {
        try {
            return SecurityUtils.getSubject();
        } catch (final UnavailableSecurityManagerException e) {
            // If the call is an internal one then there won't be a security manager.
            LOGGER.debug(e.getMessage(), e);
        }

        return null;
    }

    private UserRef getUserRef() {
        UserRef userRef = CurrentUserState.currentUserRef();
        try {
            if (userRef == null) {
                final Subject subject = getSubject();
                if (subject != null && subject.isAuthenticated()) {
                    final User user = (User) subject.getPrincipal();
                    if (user != null) {
                        userRef = UserRef.create(user);
                    }
                }
            }
        } catch (final InvalidSessionException e) {
            // If the session has expired then the user will need to login again.
            LOGGER.debug(e.getMessage(), e);
        }

        return userRef;
    }

    @Override
    public String getUserId() {
        final UserRef userRef = getUserRef();
        if (userRef == null) {
            return null;
        }
        return userRef.getName();
    }

    @Override
    public String getToken() {
        try {
            return JWT
                    .create()
                    .withIssuer(JWTUtils.ISSUER)
                    .withSubject(getUserId())
                    .sign(Algorithm.HMAC256(JWTUtils.SECRET));
        } catch (final JWTCreationException e) {
            LOGGER.error(e.getMessage(), e);
        } catch (final UnsupportedEncodingException e) {
            LOGGER.error(e.getMessage(), e);
        }

        return null;
    }

    @Override
    public boolean isLoggedIn() {
        return getUserRef() != null;
    }

    @Override
    public boolean isAdmin() {
        return hasAppPermission(PermissionNames.ADMINISTRATOR);
    }

    @Override
    public void elevatePermissions() {
        CurrentUserState.elevatePermissions();
    }

    @Override
    public void restorePermissions() {
        CurrentUserState.restorePermissions();
    }

    @Override
    public boolean hasAppPermission(final String permission) {
        // Get the current user.
        final UserRef userRef = getUserRef();

        // If there is no logged in user then throw an exception.
        if (userRef == null) {
            throw new AuthenticationServiceException("No user is currently logged in");
        }

        // If the user is the internal processing user then they automatically have permission.
        if (INTERNAL_PROCESSING_USER.equals(userRef)) {
            return true;
        }

        final UserPermissions userPermissions = userPermissionCache.get(userRef);
        boolean result = userPermissions.hasAppPermission(permission);

        // If the user doesn't have the requested permission see if they are an admin.
        if (!result && !PermissionNames.ADMINISTRATOR.equals(permission)) {
            result = userPermissions.hasAppPermission(PermissionNames.ADMINISTRATOR);
        }

        return result;
    }

    @Override
    public boolean hasDocumentPermission(final String documentType, final String documentId, final String permission) {
        // Let administrators do anything.
        if (isAdmin()) {
            return true;
        }

        // Get the current user.
        final UserRef userRef = getUserRef();

        // If there is no logged in user then throw an exception.
        if (userRef == null) {
            throw new AuthenticationServiceException("No user is currently logged in");
        }

        final UserPermissions userPermissions = userPermissionCache.get(userRef);
        boolean result = userPermissions.hasDocumentPermission(documentType, documentId, permission);

        // If the user doesn't have read permission then check to see if the current task has been set to have elevated permissions.
        if (!result && DocumentPermissionNames.READ.equals(permission)) {
            if (CurrentUserState.isElevatePermissions()) {
                result = userPermissions.hasDocumentPermission(documentType, documentId, DocumentPermissionNames.USE);
            }
        }

        return result;
    }

    @Override
    public void clearDocumentPermissions(final String documentType, final String documentUuid) {
        // Get the current user.
        final UserRef userRef = getUserRef();

        // If no user is present then don't create permissions.
        if (userRef != null) {
            if (hasDocumentPermission(documentType, documentUuid, DocumentPermissionNames.OWNER)) {
                final DocRef docRef = new DocRef(documentType, documentUuid);
                documentPermissionService.clearDocumentPermissions(docRef);

                // Make sure the cache updates for the current user.
                userPermissionCache.remove(userRef);
                // Make sure cache updates for the document.
                documentPermissionsCache.remove(docRef);
            }
        }
    }

    @Override
    public void addDocumentPermissions(final String sourceType, final String sourceUuid, final String documentType, final String documentUuid, final boolean owner) {
        // Get the current user.
        final UserRef userRef = getUserRef();

        // If no user is present then don't create permissions.
        if (userRef != null) {
            if (owner || hasDocumentPermission(documentType, documentUuid, DocumentPermissionNames.OWNER)) {
                final DocRef docRef = new DocRef(documentType, documentUuid);

                if (owner) {
                    // Make the current user the owner of the new document.
                    try {
                        documentPermissionService.addPermission(userRef, docRef, DocumentPermissionNames.OWNER);
                    } catch (final RollbackException | TransactionException e) {
                        LOGGER.debug(e.getMessage(), e);
                    } catch (final Exception e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }

                // Inherit permissions from the parent folder if there is one.
                // TODO : This should be part of the explorer service.
                copyPermissions(sourceType, sourceUuid, documentType, documentUuid);

                // Make sure the cache updates for the current user.
                userPermissionCache.remove(userRef);
                // Make sure cache updates for the document.
                documentPermissionsCache.remove(docRef);
            }
        }
    }

    private void copyPermissions(final String sourceType, final String sourceUuid, final String destType, final String destUuid) {
        if (sourceType != null && sourceUuid != null) {
            final DocRef sourceDocRef = new DocRef(sourceType, sourceUuid);

            final DocumentPermissions documentPermissions = documentPermissionService.getPermissionsForDocument(sourceDocRef);
            if (documentPermissions != null) {
                final Map<UserRef, Set<String>> userPermissions = documentPermissions.getUserPermissions();
                if (userPermissions != null && userPermissions.size() > 0) {

                    final DocRef destDocRef = new DocRef(destType, destUuid);
                    final EntityService<?> entityService = genericEntityService.getEntityService(destDocRef.getType());
                    if (entityService != null && entityService instanceof DocumentEntityService) {
                        final DocumentEntityService<?> documentEntityService = (DocumentEntityService) entityService;
                        final String[] allowedPermissions = documentEntityService.getPermissions();

                        for (final Map.Entry<UserRef, Set<String>> entry : userPermissions.entrySet()) {
                            final UserRef userRef = entry.getKey();
                            final Set<String> permissions = entry.getValue();

                            for (final String allowedPermission : allowedPermissions) {
                                if (permissions.contains(allowedPermission)) {
//                                    // Don't allow owner permissions to be inherited.
//                                    if (!DocumentPermissionNames.OWNER.equals(allowedPermission)) {
                                    try {
                                        documentPermissionService.addPermission(userRef, destDocRef, allowedPermission);
                                    } catch (final RollbackException | TransactionException e) {
                                        LOGGER.debug(e.getMessage(), e);
                                    } catch (final Exception e) {
                                        LOGGER.error(e.getMessage(), e);
                                    }
//                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
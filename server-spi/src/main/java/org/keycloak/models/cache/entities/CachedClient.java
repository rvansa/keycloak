/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.cache.entities;

import org.keycloak.models.ClientModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.cache.RealmCache;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class CachedClient implements Serializable {

    protected String id;
    protected String clientId;
    protected String name;
    protected String description;
    protected String realm;
    protected Set<String> redirectUris = new HashSet<String>();
    protected boolean enabled;
    protected String clientAuthenticatorType;
    protected String secret;
    protected String registrationToken;
    protected String protocol;
    protected Map<String, String> attributes = new HashMap<String, String>();
    protected boolean publicClient;
    protected boolean fullScopeAllowed;
    protected boolean frontchannelLogout;
    protected int notBefore;
    protected Set<String> scope = new HashSet<String>();
    protected Set<String> webOrigins = new HashSet<String>();
    protected Set<ProtocolMapperModel> protocolMappers = new HashSet<ProtocolMapperModel>();
    protected boolean surrogateAuthRequired;
    protected String managementUrl;
    protected String rootUrl;
    protected String baseUrl;
    protected List<String> defaultRoles = new LinkedList<String>();
    protected boolean bearerOnly;
    protected boolean consentRequired;
    protected boolean standardFlowEnabled;
    protected boolean implicitFlowEnabled;
    protected boolean directAccessGrantsEnabled;
    protected boolean serviceAccountsEnabled;
    protected Map<String, String> roles = new HashMap<String, String>();
    protected int nodeReRegistrationTimeout;
    protected Map<String, Integer> registeredNodes;
    protected String clientTemplate;
    protected boolean useTemplateScope;
    protected boolean useTemplateConfig;
    protected boolean useTemplateMappers;

    public CachedClient(RealmCache cache, RealmProvider delegate, RealmModel realm, ClientModel model) {
        id = model.getId();
        clientAuthenticatorType = model.getClientAuthenticatorType();
        secret = model.getSecret();
        registrationToken = model.getRegistrationToken();
        clientId = model.getClientId();
        name = model.getName();
        description = model.getDescription();
        this.realm = realm.getId();
        enabled = model.isEnabled();
        protocol = model.getProtocol();
        attributes.putAll(model.getAttributes());
        notBefore = model.getNotBefore();
        frontchannelLogout = model.isFrontchannelLogout();
        publicClient = model.isPublicClient();
        fullScopeAllowed = model.isFullScopeAllowed();
        redirectUris.addAll(model.getRedirectUris());
        webOrigins.addAll(model.getWebOrigins());
        for (RoleModel role : model.getScopeMappings())  {
            scope.add(role.getId());
        }
        for (ProtocolMapperModel mapper : model.getProtocolMappers()) {
            this.protocolMappers.add(mapper);
        }
        surrogateAuthRequired = model.isSurrogateAuthRequired();
        managementUrl = model.getManagementUrl();
        rootUrl = model.getRootUrl();
        baseUrl = model.getBaseUrl();
        defaultRoles.addAll(model.getDefaultRoles());
        bearerOnly = model.isBearerOnly();
        consentRequired = model.isConsentRequired();
        standardFlowEnabled = model.isStandardFlowEnabled();
        implicitFlowEnabled = model.isImplicitFlowEnabled();
        directAccessGrantsEnabled = model.isDirectAccessGrantsEnabled();
        serviceAccountsEnabled = model.isServiceAccountsEnabled();
        cacheRoles(cache, realm, model);

        nodeReRegistrationTimeout = model.getNodeReRegistrationTimeout();
        registeredNodes = new TreeMap<String, Integer>(model.getRegisteredNodes());
        if (model.getClientTemplate() != null) {
            clientTemplate = model.getClientTemplate().getId();
        }
        useTemplateConfig = model.useTemplateConfig();
        useTemplateMappers = model.useTemplateMappers();
        useTemplateScope = model.useTemplateScope();
    }

    protected void cacheRoles(RealmCache cache, RealmModel realm, ClientModel model) {
        for (RoleModel role : model.getRoles()) {
            roles.put(role.getName(), role.getId());
            cache.addRole(new CachedClientRole(id, role, realm));
        }
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getRealm() {
        return realm;
    }

    public Set<String> getRedirectUris() {
        return redirectUris;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getClientAuthenticatorType() {
        return clientAuthenticatorType;
    }

    public String getSecret() {
        return secret;
    }

    public String getRegistrationToken() {
        return registrationToken;
    }

    public boolean isPublicClient() {
        return publicClient;
    }

    public int getNotBefore() {
        return notBefore;
    }

    public Set<String> getScope() {
        return scope;
    }

    public Set<String> getWebOrigins() {
        return webOrigins;
    }

    public boolean isFullScopeAllowed() {
        return fullScopeAllowed;
    }

    public String getProtocol() {
        return protocol;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public boolean isFrontchannelLogout() {
        return frontchannelLogout;
    }

    public Set<ProtocolMapperModel> getProtocolMappers() {
        return protocolMappers;
    }

    public boolean isSurrogateAuthRequired() {
        return surrogateAuthRequired;
    }

    public String getManagementUrl() {
        return managementUrl;
    }

    public String getRootUrl() {
        return rootUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public List<String> getDefaultRoles() {
        return defaultRoles;
    }

    public boolean isBearerOnly() {
        return bearerOnly;
    }

    public boolean isConsentRequired() {
        return consentRequired;
    }

    public boolean isStandardFlowEnabled() {
        return standardFlowEnabled;
    }

    public boolean isImplicitFlowEnabled() {
        return implicitFlowEnabled;
    }

    public boolean isDirectAccessGrantsEnabled() {
        return directAccessGrantsEnabled;
    }

    public boolean isServiceAccountsEnabled() {
        return serviceAccountsEnabled;
    }

    public Map<String, String> getRoles() {
        return roles;
    }

    public int getNodeReRegistrationTimeout() {
        return nodeReRegistrationTimeout;
    }

    public Map<String, Integer> getRegisteredNodes() {
        return registeredNodes;
    }

    public String getClientTemplate() {
        return clientTemplate;
    }

    public boolean isUseTemplateScope() {
        return useTemplateScope;
    }

    public boolean isUseTemplateConfig() {
        return useTemplateConfig;
    }

    public boolean isUseTemplateMappers() {
        return useTemplateMappers;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("CachedClient{");
        sb.append("id='").append(id).append('\'');
        sb.append(", clientId='").append(clientId).append('\'');
        sb.append(", name='").append(name).append('\'');
        sb.append(", description='").append(description).append('\'');
        sb.append(", realm='").append(realm).append('\'');
        sb.append(", redirectUris=").append(redirectUris);
        sb.append(", enabled=").append(enabled);
        sb.append(", clientAuthenticatorType='").append(clientAuthenticatorType).append('\'');
        sb.append(", secret='").append(secret).append('\'');
        sb.append(", registrationToken='").append(registrationToken).append('\'');
        sb.append(", protocol='").append(protocol).append('\'');
        sb.append(", attributes=").append(attributes);
        sb.append(", publicClient=").append(publicClient);
        sb.append(", fullScopeAllowed=").append(fullScopeAllowed);
        sb.append(", frontchannelLogout=").append(frontchannelLogout);
        sb.append(", notBefore=").append(notBefore);
        sb.append(", scope=").append(scope);
        sb.append(", webOrigins=").append(webOrigins);
        sb.append(", protocolMappers=").append(protocolMappers);
        sb.append(", surrogateAuthRequired=").append(surrogateAuthRequired);
        sb.append(", managementUrl='").append(managementUrl).append('\'');
        sb.append(", rootUrl='").append(rootUrl).append('\'');
        sb.append(", baseUrl='").append(baseUrl).append('\'');
        sb.append(", defaultRoles=").append(defaultRoles);
        sb.append(", bearerOnly=").append(bearerOnly);
        sb.append(", consentRequired=").append(consentRequired);
        sb.append(", standardFlowEnabled=").append(standardFlowEnabled);
        sb.append(", implicitFlowEnabled=").append(implicitFlowEnabled);
        sb.append(", directAccessGrantsEnabled=").append(directAccessGrantsEnabled);
        sb.append(", serviceAccountsEnabled=").append(serviceAccountsEnabled);
        sb.append(", roles=").append(roles);
        sb.append(", nodeReRegistrationTimeout=").append(nodeReRegistrationTimeout);
        sb.append(", registeredNodes=").append(registeredNodes);
        sb.append(", clientTemplate='").append(clientTemplate).append('\'');
        sb.append(", useTemplateScope=").append(useTemplateScope);
        sb.append(", useTemplateConfig=").append(useTemplateConfig);
        sb.append(", useTemplateMappers=").append(useTemplateMappers);
        sb.append('}');
        return sb.toString();
    }
}

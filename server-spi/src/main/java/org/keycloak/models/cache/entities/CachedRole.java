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

import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class CachedRole implements Serializable {

    final protected String id;
    final protected String name;
    final protected String realm;
    final protected String description;
    final protected Boolean scopeParamRequired;
    final protected boolean composite;
    final protected Set<String> composites = new HashSet<String>();

    public CachedRole(RoleModel model, RealmModel realm) {
        composite = model.isComposite();
        description = model.getDescription();
        id = model.getId();
        name = model.getName();
        scopeParamRequired = model.isScopeParamRequired();
        this.realm = realm.getId();
        if (composite) {
            for (RoleModel child : model.getComposites()) {
                composites.add(child.getId());
            }
        }

    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getRealm() {
        return realm;
    }

    public String getDescription() {
        return description;
    }

    public Boolean isScopeParamRequired() {
        return scopeParamRequired;
    }

    public boolean isComposite() {
        return composite;
    }

    public Set<String> getComposites() {
        return composites;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("CachedRole{");
        sb.append("id='").append(id).append('\'');
        sb.append(", name='").append(name).append('\'');
        sb.append(", realm='").append(realm).append('\'');
        sb.append(", description='").append(description).append('\'');
        sb.append(", scopeParamRequired=").append(scopeParamRequired);
        sb.append(", composite=").append(composite);
        sb.append(", composites=").append(composites);
        sb.append('}');
        return sb.toString();
    }
}

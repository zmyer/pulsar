/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.authentication;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import javax.naming.AuthenticationException;
import javax.servlet.http.HttpServletRequest;

import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

/**
 * Authentication service
 *
 */
public class AuthenticationService implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationService.class);

    private final Map<String, AuthenticationProvider> providers = Maps.newHashMap();

    public AuthenticationService(ServiceConfiguration conf) throws PulsarServerException {
        if (conf.isAuthenticationEnabled()) {
            try {
                AuthenticationProvider provider;
                for (String className : conf.getAuthenticationProviders()) {
                    if (className.isEmpty()) {
                        continue;
                    }
                    provider = (AuthenticationProvider) Class.forName(className).newInstance();
                    provider.initialize(conf);
                    providers.put(provider.getAuthMethodName(), provider);
                    LOG.info("{} has been loaded.", className);
                }
                if (providers.isEmpty()) {
                    LOG.warn("No authentication providers are loaded.");
                }
            } catch (Throwable e) {
                throw new PulsarServerException("Failed to load an authentication provider.", e);
            }
        } else {
            LOG.info("Authentication is disabled");
        }
    }

    public String authenticate(AuthenticationDataSource authData, String authMethodName)
            throws AuthenticationException {
        AuthenticationProvider provider = providers.get(authMethodName);
        if (provider != null) {
            return provider.authenticate(authData);
        } else {
            throw new AuthenticationException("Unsupported authentication mode: " + authMethodName);
        }
    }

    public String authenticateHttpRequest(HttpServletRequest request) throws AuthenticationException {
        // Try to validate with any configured provider
        AuthenticationDataSource authData = new AuthenticationDataHttps(request);
        for (AuthenticationProvider provider : providers.values()) {
            try {
                return provider.authenticate(authData);
            } catch (AuthenticationException e) {
                // Ignore the exception because we don't know which authentication method is expected here.
            }
        }

        // No authentication provided
        if (!providers.isEmpty()) {
            // If at least a provider was configured, then the authentication needs to be provider
            throw new AuthenticationException("Authentication required");
        } else {
            // No authentication required
            return "<none>";
        }
    }

    @Override
    public void close() throws IOException {
        for (AuthenticationProvider provider : providers.values()) {
            provider.close();
        }
    }
}

/*
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
package org.apache.nifi.ranger.authorization;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.nifi.authorization.AuthorizationAuditor;
import org.apache.nifi.authorization.AuthorizationRequest;
import org.apache.nifi.authorization.AuthorizationResult;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.AuthorizerConfigurationContext;
import org.apache.nifi.authorization.AuthorizerInitializationContext;
import org.apache.nifi.authorization.UserContextKeys;
import org.apache.nifi.authorization.annotation.AuthorizerContext;
import org.apache.nifi.authorization.exception.AuthorizationAccessException;
import org.apache.nifi.authorization.exception.AuthorizerCreationException;
import org.apache.nifi.authorization.exception.AuthorizerDestructionException;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.deprecation.log.DeprecationLogger;
import org.apache.nifi.deprecation.log.DeprecationLoggerFactory;
import org.apache.nifi.util.NiFiProperties;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.authorization.hadoop.config.RangerPluginConfig;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authorizer implementation that uses Apache Ranger to make authorization decisions.
 */
public class RangerNiFiAuthorizer implements Authorizer, AuthorizationAuditor {
    private static final Logger logger = LoggerFactory.getLogger(RangerNiFiAuthorizer.class);

    static final String RANGER_AUDIT_PATH_PROP = "Ranger Audit Config Path";
    static final String RANGER_SECURITY_PATH_PROP = "Ranger Security Config Path";
    static final String RANGER_KERBEROS_ENABLED_PROP = "Ranger Kerberos Enabled";
    static final String RANGER_SERVICE_TYPE_PROP = "Ranger Service Type";
    static final String RANGER_APP_ID_PROP = "Ranger Application Id";
    static final String RANGER_ADMIN_IDENTITY_PROP_PREFIX = "Ranger Admin Identity";
    static final Pattern RANGER_ADMIN_IDENTITY_PATTERN = Pattern.compile(RANGER_ADMIN_IDENTITY_PROP_PREFIX + "\\s?\\S*");

    static final String RANGER_NIFI_RESOURCE_NAME = "nifi-resource";
    static final String DEFAULT_SERVICE_TYPE = "nifi";
    static final String DEFAULT_APP_ID = "nifi";
    static final String RESOURCES_RESOURCE = "/resources";
    static final String HADOOP_SECURITY_AUTHENTICATION = "hadoop.security.authentication";
    static final String KERBEROS_AUTHENTICATION = "kerberos";

    private final Map<AuthorizationRequest, RangerAccessResult> resultLookup = new WeakHashMap<>();

    private volatile RangerBasePluginWithPolicies nifiPlugin = null;
    private volatile RangerDefaultAuditHandler defaultAuditHandler = null;
    private volatile Set<String> rangerAdminIdentity = null;
    private volatile boolean rangerKerberosEnabled = false;
    private volatile NiFiProperties nifiProperties;
    private final NumberFormat numberFormat = NumberFormat.getInstance();

    private final DeprecationLogger deprecationLogger = DeprecationLoggerFactory.getLogger(getClass());

    @Override
    public void initialize(AuthorizerInitializationContext initializationContext) throws AuthorizerCreationException {
        deprecationLogger.warn("Apache Ranger integration does not support Jetty 12 and related libraries required for NiFi 2.0");
    }

    @Override
    public void onConfigured(AuthorizerConfigurationContext configurationContext) throws AuthorizerCreationException {
        try {
            if (nifiPlugin == null) {
                logger.info("RangerNiFiAuthorizer(): initializing base plugin");

                final String serviceType = getConfigValue(configurationContext, RANGER_SERVICE_TYPE_PROP, DEFAULT_SERVICE_TYPE);
                final String appId = getConfigValue(configurationContext, RANGER_APP_ID_PROP, DEFAULT_APP_ID);

                nifiPlugin = createRangerBasePlugin(serviceType, appId);

                final RangerPluginConfig pluginConfig = nifiPlugin.getConfig();

                final PropertyValue securityConfigValue = configurationContext.getProperty(RANGER_SECURITY_PATH_PROP);
                addRequiredResource(RANGER_SECURITY_PATH_PROP, securityConfigValue, pluginConfig);

                final PropertyValue auditConfigValue = configurationContext.getProperty(RANGER_AUDIT_PATH_PROP);
                addRequiredResource(RANGER_AUDIT_PATH_PROP, auditConfigValue, pluginConfig);

                final String rangerKerberosEnabledValue = getConfigValue(configurationContext, RANGER_KERBEROS_ENABLED_PROP, Boolean.FALSE.toString());
                rangerKerberosEnabled = rangerKerberosEnabledValue.equals(Boolean.TRUE.toString()) ? true : false;

                if (rangerKerberosEnabled) {
                    // configure UGI for when RangerAdminRESTClient calls UserGroupInformation.isSecurityEnabled()
                    final Configuration securityConf = new Configuration();
                    securityConf.set(HADOOP_SECURITY_AUTHENTICATION, KERBEROS_AUTHENTICATION);
                    UserGroupInformation.setConfiguration(securityConf);

                    // login with the nifi principal and keytab, RangerAdminRESTClient will use Ranger's MiscUtil which
                    // will grab UserGroupInformation.getLoginUser() and call ugi.checkTGTAndReloginFromKeytab();
                    final String nifiPrincipal = nifiProperties.getKerberosServicePrincipal();
                    final String nifiKeytab = nifiProperties.getKerberosServiceKeytabLocation();

                    if (StringUtils.isBlank(nifiPrincipal) || StringUtils.isBlank(nifiKeytab)) {
                        throw new AuthorizerCreationException("Principal and Keytab must be provided when Kerberos is enabled");
                    }

                    UserGroupInformation.loginUserFromKeytab(nifiPrincipal.trim(), nifiKeytab.trim());
                }

                nifiPlugin.init();

                defaultAuditHandler = new RangerDefaultAuditHandler();
                rangerAdminIdentity = getConfigValues(configurationContext, RANGER_ADMIN_IDENTITY_PATTERN, null);

            } else {
                logger.info("RangerNiFiAuthorizer(): base plugin already initialized");
            }
        } catch (Throwable t) {
            throw new AuthorizerCreationException("Error creating RangerBasePlugin", t);
        }
    }

    protected RangerBasePluginWithPolicies createRangerBasePlugin(final String serviceType, final String appId) {
        return new RangerBasePluginWithPolicies(serviceType, appId);
    }

    @Override
    public AuthorizationResult authorize(final AuthorizationRequest request) throws AuthorizationAccessException {
        final String identity = request.getIdentity();
        final Set<String> userGroups = request.getGroups();
        final String resourceIdentifier = request.getResource().getIdentifier();

        // if a ranger admin identity was provided, and it contains the identity making the request,
        // and the request is to retrieve the resources, then allow it through
        if (rangerAdminIdentity != null && rangerAdminIdentity.contains(identity)
                && resourceIdentifier.equals(RESOURCES_RESOURCE)) {
            return AuthorizationResult.approved();
        }

        final String clientIp;
        if (request.getUserContext() != null) {
            clientIp = request.getUserContext().get(UserContextKeys.CLIENT_ADDRESS.name());
        } else {
            clientIp = null;
        }

        final RangerAccessResourceImpl resource = new RangerAccessResourceImpl();
        resource.setValue(RANGER_NIFI_RESOURCE_NAME, resourceIdentifier);

        final RangerAccessRequestImpl rangerRequest = new RangerAccessRequestImpl();
        rangerRequest.setResource(resource);
        rangerRequest.setAction(request.getAction().name());
        rangerRequest.setAccessType(request.getAction().name());
        rangerRequest.setUser(identity);
        rangerRequest.setUserGroups(userGroups);
        rangerRequest.setAccessTime(new Date());

        if (!StringUtils.isBlank(clientIp)) {
            rangerRequest.setClientIPAddress(clientIp);
        }

        final long authStart = System.nanoTime();
        final RangerAccessResult result = nifiPlugin.isAccessAllowed(rangerRequest);
        final long authNanos = System.nanoTime() - authStart;
        logger.debug("Performed authorization against Ranger for Resource ID {}, Identity {} in {} nanos", resourceIdentifier, identity, numberFormat.format(authNanos));

        // store the result for auditing purposes later if appropriate
        if (request.isAccessAttempt()) {
            synchronized (resultLookup) {
                resultLookup.put(request, result);
            }
        }

        if (result != null && result.getIsAllowed()) {
            // return approved
            return AuthorizationResult.approved();
        } else {
            // if result.getIsAllowed() is false, then we need to determine if it was because no policy exists for the
            // given resource, or if it was because a policy exists but not for the given user or action
            final boolean doesPolicyExist = nifiPlugin.doesPolicyExist(request.getResource().getIdentifier(), request.getAction());

            if (doesPolicyExist) {
                final String reason = result == null ? null : result.getReason();
                if (reason != null) {
                    logger.debug(String.format("Unable to authorize %s due to %s", identity, reason));
                }

                // a policy does exist for the resource so we were really denied access here
                return AuthorizationResult.denied(request.getExplanationSupplier().get());
            } else {
                // a policy doesn't exist so return resource not found so NiFi can work back up the resource hierarchy
                return AuthorizationResult.resourceNotFound();
            }
        }
    }

    @Override
    public void auditAccessAttempt(final AuthorizationRequest request, final AuthorizationResult result) {
        final RangerAccessResult rangerResult;
        synchronized (resultLookup) {
            rangerResult = resultLookup.remove(request);
        }

        if (rangerResult != null && rangerResult.getIsAudited()) {
            AuthzAuditEvent event = defaultAuditHandler.getAuthzEvents(rangerResult);

            // update the event with the originally requested resource
            event.setResourceType(RANGER_NIFI_RESOURCE_NAME);
            event.setResourcePath(request.getRequestedResource().getIdentifier());

            final long start = System.nanoTime();
            defaultAuditHandler.logAuthzAudit(event);
            final long nanos = System.nanoTime() - start;
            logger.debug("Logged authorization audits to Ranger in {} nanos", numberFormat.format(nanos));
        }
    }

    @Override
    public void preDestruction() throws AuthorizerDestructionException {
        if (nifiPlugin != null) {
            try {
                nifiPlugin.cleanup();
                nifiPlugin = null;
            } catch (Throwable t) {
                throw new AuthorizerDestructionException("Error cleaning up RangerBasePlugin", t);
            }
        }
    }

    @AuthorizerContext
    public void setNiFiProperties(final NiFiProperties properties) {
        this.nifiProperties = properties;
    }

    /**
     * Adds a resource to the RangerConfiguration singleton so it is already there by the time RangerBasePlugin.init()
     * is called.
     *
     * @param name the name of the given PropertyValue from the AuthorizationConfigurationContext
     * @param resourceValue the value for the given name, should be a full path to a file
     * @param configuration the RangerConfiguration instance to add the resource to
     */
    private void addRequiredResource(final String name, final PropertyValue resourceValue, final RangerConfiguration configuration) {
        if (resourceValue == null || StringUtils.isBlank(resourceValue.getValue())) {
            throw new AuthorizerCreationException(name + " must be specified.");
        }

        final File resourceFile = new File(resourceValue.getValue());
        if (!resourceFile.exists() || !resourceFile.canRead()) {
            throw new AuthorizerCreationException(resourceValue + " does not exist, or can not be read");
        }

        try {
            configuration.addResource(resourceFile.toURI().toURL());
        } catch (MalformedURLException e) {
            throw new AuthorizerCreationException("Error creating URI for " + resourceValue, e);
        }
    }

    private String getConfigValue(final AuthorizerConfigurationContext context, final String name, final String defaultValue) {
        final PropertyValue configValue = context.getProperty(name);

        String retValue = defaultValue;
        if (configValue != null && !StringUtils.isBlank(configValue.getValue())) {
            retValue = configValue.getValue();
        }

        return retValue;
    }

    private Set<String> getConfigValues(final AuthorizerConfigurationContext context, final Pattern namePattern, final String defaultValue) {
        final Set<String> configValues = new HashSet<>();

        for (Map.Entry<String,String> entry : context.getProperties().entrySet()) {
            Matcher matcher = namePattern.matcher(entry.getKey());
            if (matcher.matches() && !StringUtils.isBlank(entry.getValue())) {
                configValues.add(entry.getValue());
            }
        }

        if (configValues.isEmpty() && (defaultValue != null)) {
            configValues.add(defaultValue);
        }

        return configValues;
    }
}

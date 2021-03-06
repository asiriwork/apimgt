/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.hostobjects;

import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.woden.WSDLFactory;
import org.apache.woden.WSDLReader;
import org.jaggeryjs.hostobjects.file.FileHostObject;
import org.jaggeryjs.scriptengine.exceptions.ScriptException;
import org.mozilla.javascript.*;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.dto.UserApplicationAPIUsage;
import org.wso2.carbon.apimgt.api.model.*;
import org.wso2.carbon.apimgt.hostobjects.internal.HostObjectComponent;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.UserAwareAPIProvider;
import org.wso2.carbon.apimgt.impl.dto.Environment;
import org.wso2.carbon.apimgt.impl.dto.TierPermissionDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIAuthenticationAdminClient;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.APIVersionComparator;
import org.wso2.carbon.apimgt.impl.utils.APIVersionStringComparator;
import org.wso2.carbon.apimgt.keymgt.client.SubscriberKeyMgtClient;
import org.wso2.carbon.apimgt.usage.client.APIUsageStatisticsClient;
import org.wso2.carbon.apimgt.usage.client.dto.*;
import org.wso2.carbon.apimgt.usage.client.exception.APIMgtUsageQueryServiceClientException;
import org.wso2.carbon.authenticator.stub.AuthenticationAdminStub;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.user.mgt.stub.UserAdminStub;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.net.ssl.SSLHandshakeException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;

@SuppressWarnings("unused")
public class APIProviderHostObject extends ScriptableObject {

    private static final Log log = LogFactory.getLog(APIProviderHostObject.class);

    private String username;

    private APIProvider apiProvider;

    public String getClassName() {
        return "APIProvider";
    }

    // The zero-argument constructor used for create instances for runtime
    public APIProviderHostObject() throws APIManagementException {

    }

    public APIProviderHostObject(String loggedUser) throws APIManagementException {
        username = loggedUser;
        apiProvider = APIManagerFactory.getInstance().getAPIProvider(loggedUser);
    }

    public String getUsername() {
        return username;
    }

    public static Scriptable jsConstructor(Context cx, Object[] args, Function Obj,
                                           boolean inNewExpr)
            throws APIManagementException {


        if (args!=null && args.length != 0) {
            String username = (String) args[0];
            return new APIProviderHostObject(username);
        }
        return new APIProviderHostObject();
    }

    public APIProvider getApiProvider() {
        return apiProvider;
    }

    private static APIProvider getAPIProvider(Scriptable thisObj) {
        return ((APIProviderHostObject) thisObj).getApiProvider();
    }

    private static void handleException(String msg) throws APIManagementException {
        log.error(msg);
        throw new APIManagementException(msg);
    }

    private static void handleException(String msg, Throwable t) throws APIManagementException {
        log.error(msg, t);
        throw new APIManagementException(msg, t);
    }

    public static NativeObject jsFunction_login(Context cx, Scriptable thisObj,
                                                Object[] args, Function funObj)
            throws APIManagementException {

        if (args==null || args.length == 0 || !isStringValues(args)) {
            handleException("Invalid input parameters to the login method");
        }

        String username = (String) args[0];
        String password = (String) args[1];

        APIManagerConfiguration config = HostObjectComponent.getAPIManagerConfiguration();
        String url = config.getFirstProperty(APIConstants.AUTH_MANAGER_URL);
        if (url == null) {
            handleException("API key manager URL unspecified");
        }

        NativeObject row = new NativeObject();
        try {

            UserAdminStub userAdminStub = new UserAdminStub(url + "UserAdmin");
            CarbonUtils.setBasicAccessSecurityHeaders(username, password,
                                                      true, userAdminStub._getServiceClient());
            //If multiple user stores are in use, and if the user hasn't specified the domain to which
            //he needs to login to
            /* Below condition is commented out as per new multiple users-store implementation,users from
            different user-stores not needed to input domain names when tried to login,APIMANAGER-1392*/
           // if (userAdminStub.hasMultipleUserStores() && !username.contains("/")) {
           //      handleException("Domain not specified. Please provide your username as domain/username");
           // }
        } catch (Exception e) {
            log.error("Error occurred while checking for multiple user stores");
        }

        try {
            AuthenticationAdminStub authAdminStub = new AuthenticationAdminStub(null, url + "AuthenticationAdmin");
            ServiceClient client = authAdminStub._getServiceClient();
            Options options = client.getOptions();
            options.setManageSession(true);

            String host = new URL(url).getHost();
            if (!authAdminStub.login(username, password, host)) {
                handleException("Login failed! Please recheck the username and password and try again..");
            }
            ServiceContext serviceContext = authAdminStub.
                    _getServiceClient().getLastOperationContext().getServiceContext();
            String sessionCookie = (String) serviceContext.getProperty(HTTPConstants.COOKIE_STRING);

            String usernameWithDomain = APIUtil.getLoggedInUserInfo(sessionCookie,url).getUserName();
            usernameWithDomain = APIUtil.setDomainNameToUppercase(usernameWithDomain);
            String tenantDomain = MultitenantUtils.getTenantDomain(username);
            boolean isSuperTenant = false;
            
            if (tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
            	isSuperTenant = true;
            }else {
                usernameWithDomain = usernameWithDomain+"@"+tenantDomain;
            }

            boolean   authorized =
                    APIUtil.checkPermissionQuietly(usernameWithDomain, APIConstants.Permissions.API_CREATE) ||
                            APIUtil.checkPermissionQuietly(usernameWithDomain, APIConstants.Permissions.API_PUBLISH);


            if (authorized) {

                row.put("user", row, usernameWithDomain);
                row.put("sessionId", row, sessionCookie);
                row.put("isSuperTenant", row, isSuperTenant);
                row.put("error", row, false);
            } else {
                handleException("Login failed! Insufficient privileges.");
            }
        } catch (Exception e) {
            row.put("error", row, true);
            row.put("detail", row, e.getMessage());
        }

        return row;
    }

    public static String jsFunction_getAuthServerURL(Context cx, Scriptable thisObj,
                                                     Object[] args, Function funObj)
            throws APIManagementException {

        APIManagerConfiguration config = HostObjectComponent.getAPIManagerConfiguration();
        String url = config.getFirstProperty(APIConstants.AUTH_MANAGER_URL);
        if (url == null) {
            handleException("API key manager URL unspecified");
        }
        return url;
    }

    public static String jsFunction_getHTTPsURL(Context cx, Scriptable thisObj,
                                                Object[] args, Function funObj)
            throws APIManagementException {
        String hostName = CarbonUtils.getServerConfiguration().getFirstProperty("HostName");
        String backendHttpsPort = HostObjectUtils.getBackendPort("https");
        if (hostName == null) {
            hostName = System.getProperty("carbon.local.ip");
        }
        return "https://" + hostName + ":" + backendHttpsPort;

    }

    /**
     * This method is to functionality of add a new API in API-Provider
     *
     * @param cx      Rhino context
     * @param thisObj Scriptable object
     * @param args    Passing arguments
     * @param funObj  Function object
     * @return true if the API was added successfully
     * @throws APIManagementException Wrapped exception by org.wso2.carbon.apimgt.api.APIManagementException
     */
    public static boolean jsFunction_addAPI(Context cx, Scriptable thisObj,
                                            Object[] args,
                                            Function funObj)
            throws APIManagementException, ScriptException {
        if (args==null||args.length == 0) {
            handleException("Invalid number of input parameters.");
        }

        boolean success;
        NativeObject apiData = (NativeObject) args[0];
        String provider = String.valueOf(apiData.get("provider", apiData));
        if (provider != null) {
            provider = APIUtil.replaceEmailDomain(provider);
        }
        String name = (String) apiData.get("apiName", apiData);
        String version = (String) apiData.get("version", apiData);
        String description = (String) apiData.get("description", apiData);
        String endpoint = (String) apiData.get("endpoint", apiData);
        String sandboxUrl = (String) apiData.get("sandbox", apiData);
        String visibility = (String) apiData.get("visibility", apiData);
        String visibleRoles = "";


        if (visibility != null && visibility.equals(APIConstants.API_RESTRICTED_VISIBILITY)) {
        	visibleRoles = (String) apiData.get("visibleRoles", apiData);
        }

        String visibleTenants = "";
        if (visibility != null && visibility.equals(APIConstants.API_CONTROLLED_VISIBILITY)) {
        	visibleTenants = (String) apiData.get("visibleTenants", apiData);
        }

        if (sandboxUrl != null && sandboxUrl.trim().length() == 0) {
            sandboxUrl = null;
        }

        if (endpoint != null && endpoint.trim().length() == 0) {
            endpoint = null;
        }

        if(endpoint != null && !endpoint.startsWith("http") && !endpoint.startsWith("https")){
            endpoint = "http://" + endpoint;
        }
        if(sandboxUrl != null && !sandboxUrl.startsWith("http") && !sandboxUrl.startsWith("https")){
            sandboxUrl = "http://" + sandboxUrl;
        }

        String redirectURL = (String) apiData.get("redirectURL", apiData);
        boolean advertiseOnly = Boolean.parseBoolean((String) apiData.get("advertiseOnly", apiData));
        String apiOwner = (String) apiData.get("apiOwner", apiData);
        
        if (apiOwner == null || apiOwner.equals("")) {
        	apiOwner = provider;
        }

        String wsdl = (String) apiData.get("wsdl", apiData);
        String wadl = (String) apiData.get("wadl", apiData);
        String tags = (String) apiData.get("tags", apiData);
        
        String subscriptionAvailability = (String) apiData.get("subscriptionAvailability", apiData);
        String subscriptionAvailableTenants = "";
        if (subscriptionAvailability != null && subscriptionAvailability.equals(APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS)) {
        	subscriptionAvailableTenants = (String) apiData.get("subscriptionTenants", apiData);
        }
        
        Set<String> tag = new HashSet<String>();

        if (tags != null) {
            if (tags.indexOf(",") >= 0) {
                String[] userTag = tags.split(",");
                tag.addAll(Arrays.asList(userTag).subList(0, tags.split(",").length));
            } else {
                tag.add(tags);
            }
        }

        String transport = getTransports(apiData);

        String tier = (String) apiData.get("tier", apiData);
        FileHostObject fileHostObject = (FileHostObject) apiData.get("imageUrl", apiData);
        String contextVal = (String) apiData.get("context", apiData);
        String context = contextVal.startsWith("/") ? contextVal : ("/" + contextVal);
        String providerDomain=MultitenantUtils.getTenantDomain(String.valueOf(apiData.get("provider", apiData)));
        if(!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(providerDomain))
        {
            //Create tenant aware context for API
            context= "/t/"+ providerDomain+context;
        }

        NativeArray uriTemplateArr = (NativeArray) apiData.get("uriTemplateArr", apiData);

        String techOwner = (String) apiData.get("techOwner", apiData);
        String techOwnerEmail = (String) apiData.get("techOwnerEmail", apiData);
        String bizOwner = (String) apiData.get("bizOwner", apiData);
        String bizOwnerEmail = (String) apiData.get("bizOwnerEmail", apiData);

        String endpointSecured = (String) apiData.get("endpointSecured", apiData);
        String endpointUTUsername = (String) apiData.get("endpointUTUsername", apiData);
        String endpointUTPassword = (String) apiData.get("endpointUTPassword", apiData);

        String inSequence =  (String) apiData.get("inSequence", apiData);
        String outSequence = (String) apiData.get("outSequence", apiData);
        
        String responseCache = (String) apiData.get("responseCache", apiData);
        int cacheTimeOut = APIConstants.API_RESPONSE_CACHE_TIMEOUT;
        if (APIConstants.API_RESPONSE_CACHE_ENABLED.equalsIgnoreCase(responseCache)) {
        	responseCache = APIConstants.API_RESPONSE_CACHE_ENABLED;
        	try {
        		cacheTimeOut = Integer.parseInt ((String) apiData.get("cacheTimeout", apiData));
        	} catch (NumberFormatException e) {
        		//ignore
        	}
        } else {
        	responseCache = APIConstants.API_RESPONSE_CACHE_DISABLED;
        }
        
        provider = (provider != null ? provider.trim() : null);
        name = (name != null ? name.trim() : null);
        version = (version != null ? version.trim() : null);
        APIIdentifier apiId = new APIIdentifier(provider, name, version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        
        if (apiProvider.isAPIAvailable(apiId)) {
            handleException("Error occurred while adding the API. A duplicate API already exists for " +
                            name + "-" + version);
        }

        API api = new API(apiId);
        NativeArray uriMethodArr = (NativeArray) apiData.get("uriMethodArr", apiData);
        NativeArray authTypeArr = (NativeArray) apiData.get("uriAuthMethodArr", apiData);
        NativeArray throttlingTierArr = (NativeArray) apiData.get("throttlingTierArr", apiData);
        if (uriTemplateArr != null && uriMethodArr != null && authTypeArr != null) {
            if (uriTemplateArr.getLength() == uriMethodArr.getLength()) {
                Set<URITemplate> uriTemplates = new LinkedHashSet<URITemplate>();
                for (int i = 0; i < uriTemplateArr.getLength(); i++) {
                    String uriMethods = (String) uriMethodArr.get(i, uriMethodArr);
                    String uriMethodsAuthTypes = (String) authTypeArr.get(i, authTypeArr);
                    String[] uriMethodArray = uriMethods.split(",");
                    String[] authTypeArray = uriMethodsAuthTypes.split(",");
                    String uriMethodsThrottlingTiers = (String) throttlingTierArr.get(i, throttlingTierArr);
                    String[] throttlingTierArray = uriMethodsThrottlingTiers.split(",");
                    for (int k = 0; k < uriMethodArray.length; k++) {
                        for (int j = 0; j < authTypeArray.length; j++) {
                            if (j == k) {
                                URITemplate template = new URITemplate();
                                String uriTemp = (String) uriTemplateArr.get(i, uriTemplateArr);
                                String uriTempVal = uriTemp.startsWith("/") ? uriTemp : ("/" + uriTemp);
                                template.setUriTemplate(uriTempVal);
                                String throttlingTier = throttlingTierArray[j];
                                template.setHTTPVerb(uriMethodArray[k]);
                                String authType = authTypeArray[j];
                                if (authType.equals("Application & Application User")) {
                                    authType = APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN;
                                }
                                if (authType.equals("Application User")) {
                                    authType = "Application_User";
                                }
                                template.setThrottlingTier(throttlingTier);
                                template.setAuthType(authType);
                                template.setResourceURI(endpoint);
                                template.setResourceSandboxURI(sandboxUrl);

                                uriTemplates.add(template);
                                break;
                            }

                        }
                    }

                }
                api.setUriTemplates(uriTemplates);
            }
        }

        api.setDescription(StringEscapeUtils.escapeHtml(description));
        api.setWsdlUrl(wsdl);
        api.setWadlUrl(wadl);
        api.setLastUpdated(new Date());
        api.setUrl(endpoint);
        api.setSandboxUrl(sandboxUrl);
        api.addTags(tag);
        api.setTransports(transport);
        api.setApiOwner(apiOwner);
        api.setAdvertiseOnly(advertiseOnly);
        api.setRedirectURL(redirectURL);
        api.setSubscriptionAvailability(subscriptionAvailability);
        api.setSubscriptionAvailableTenants(subscriptionAvailableTenants);
        api.setResponseCache(responseCache);
        api.setCacheTimeout(cacheTimeOut);


        if(!"none".equals(inSequence)){
            api.setInSequence(inSequence);
        }
        if(!"none".equals(outSequence)){
            api.setOutSequence(outSequence);
        }

        Set<Tier> availableTier = new HashSet<Tier>();
        String[] tierNames;
        if (tier != null) {
            tierNames = tier.split(",");
            for (String tierName : tierNames) {
                availableTier.add(new Tier(tierName));
            }
            api.addAvailableTiers(availableTier);
        }
        api.setStatus(APIStatus.CREATED);
        api.setContext(context);
        api.setBusinessOwner(bizOwner);
        api.setBusinessOwnerEmail(bizOwnerEmail);
        api.setTechnicalOwner(techOwner);
        api.setTechnicalOwnerEmail(techOwnerEmail);
        api.setVisibility(visibility);
        api.setVisibleRoles(visibleRoles != null ? visibleRoles.trim() : null);
        api.setVisibleTenants(visibleTenants != null ? visibleTenants.trim() : null);

        // @todo needs to be validated
        api.setEndpointConfig((String) apiData.get("endpoint_config", apiData));

        //set secured endpoint parameters
        if ("secured".equals(endpointSecured)) {
            api.setEndpointSecured(true);
            api.setEndpointUTUsername(endpointUTUsername);
            api.setEndpointUTPassword(endpointUTPassword);
        }

        checkFileSize(fileHostObject);
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(provider));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

            apiProvider.addAPI(api);

            if (fileHostObject != null && fileHostObject.getJavaScriptFile().getLength() != 0) {
                Icon icon = new Icon(fileHostObject.getInputStream(),
                                     fileHostObject.getJavaScriptFile().getContentType());
                String thumbPath = APIUtil.getIconPath(apiId);

                String thumbnailUrl = apiProvider.addIcon(thumbPath, icon);
                api.setThumbnailUrl(APIUtil.prependTenantPrefix(thumbnailUrl, provider));
                
                /*Set permissions to anonymous role for thumbPath*/
                APIUtil.setResourcePermissions(api.getId().getProviderName(), null, null, thumbPath);
                apiProvider.updateAPI(api);
            }
            NativeArray externalAPIStores = (NativeArray) apiData.get("externalAPIStores", apiData);
            if (externalAPIStores.getLength() != 0) {
                Set<APIStore> apiStores = new HashSet<APIStore>();
                for (int k = 0; k < externalAPIStores.getLength(); k++) {
                    String apiStoreName = externalAPIStores.get(k, externalAPIStores).toString();
                    APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                            getAPIManagerConfigurationService().getAPIManagerConfiguration();
                    APIStore apiStore = config.getExternalAPIStore(apiStoreName);
                    apiStores.add(apiStore);
                }
            apiProvider.publishToExternalAPIStores(api, apiStores);
            }
            success = true;

        } catch (Exception e) {
            handleException("Error while adding the API- " + name + "-" + version, e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;

    }

    private static String getTransports(NativeObject apiData) {
        String transportStr = String.valueOf(apiData.get("transports", apiData));
        String transport  = transportStr;
        if (transportStr != null) {
            if ((transportStr.indexOf(",") == 0) || (transportStr.indexOf(",") == (transportStr.length()-1))) {
                transport =transportStr.replace(",","");
            }
        }
        return transport;
    }


    public static boolean jsFunction_updateAPI(Context cx, Scriptable thisObj,
                                               Object[] args,
                                               Function funObj) throws APIManagementException {

        if (args==null || args.length == 0) {
            handleException("Invalid number of input parameters.");
        }

        NativeObject apiData = (NativeObject) args[0];
        boolean success;
        String provider = String.valueOf(apiData.get("provider", apiData));
        if (provider != null) {
            provider = APIUtil.replaceEmailDomain(provider);
        }
        String name = (String) apiData.get("apiName", apiData);
        String version = (String) apiData.get("version", apiData);
        String description = (String) apiData.get("description", apiData);
        FileHostObject fileHostObject = (FileHostObject) apiData.get("imageUrl", apiData);
        String endpoint = (String) apiData.get("endpoint", apiData);
        String sandboxUrl = (String) apiData.get("sandbox", apiData);
        String techOwner = (String) apiData.get("techOwner", apiData);
        String techOwnerEmail = (String) apiData.get("techOwnerEmail", apiData);
        String bizOwner = (String) apiData.get("bizOwner", apiData);
        String bizOwnerEmail = (String) apiData.get("bizOwnerEmail", apiData);
        String visibility = (String) apiData.get("visibility", apiData);
        String visibleRoles = "";
        if (visibility != null && visibility.equals(APIConstants.API_RESTRICTED_VISIBILITY)) {
        	visibleRoles = (String) apiData.get("visibleRoles", apiData);
        }
        
        String visibleTenants = "";
        if (visibility != null && visibility.equals(APIConstants.API_CONTROLLED_VISIBILITY)) {
        	visibleTenants = (String) apiData.get("visibleTenants", apiData);
        }
        String endpointSecured = (String) apiData.get("endpointSecured", apiData);
        String endpointUTUsername = (String) apiData.get("endpointUTUsername", apiData);
        String endpointUTPassword = (String) apiData.get("endpointUTPassword", apiData);

        String inSequence =  (String) apiData.get("inSequence", apiData);
        String outSequence = (String) apiData.get("outSequence", apiData);
        
        String responseCache = (String) apiData.get("responseCache", apiData);
        int cacheTimeOut = APIConstants.API_RESPONSE_CACHE_TIMEOUT;
        if (APIConstants.API_RESPONSE_CACHE_ENABLED.equalsIgnoreCase(responseCache)) {
        	responseCache = APIConstants.API_RESPONSE_CACHE_ENABLED;
        	try {
        		cacheTimeOut = Integer.parseInt ((String) apiData.get("cacheTimeout", apiData));
        	} catch (NumberFormatException e) {
        		//ignore
        	}
        } else {
        	responseCache = APIConstants.API_RESPONSE_CACHE_DISABLED;
        }
        
        if (sandboxUrl != null && sandboxUrl.trim().length() == 0) {
            sandboxUrl = null;
        }

        if (endpoint != null && endpoint.trim().length() == 0) {
            endpoint = null;
        }

        if(endpoint != null && !endpoint.startsWith("http") && !endpoint.startsWith("https")){
            endpoint = "http://" + endpoint;
        }
        if(sandboxUrl != null && !sandboxUrl.startsWith("http") && !sandboxUrl.startsWith("https")){
            sandboxUrl = "http://" + sandboxUrl;
        }
        
        String redirectURL = (String) apiData.get("redirectURL", apiData);
        boolean advertiseOnly = Boolean.parseBoolean((String) apiData.get("advertiseOnly", apiData));
        String apiOwner = (String) apiData.get("apiOwner", apiData);
        
        if (apiOwner == null || apiOwner.equals("")) {
        	apiOwner = provider;
        }

        String wsdl = (String) apiData.get("wsdl", apiData);
        String wadl = (String) apiData.get("wadl", apiData);
        String subscriptionAvailability = (String) apiData.get("subscriptionAvailability", apiData);
        String subscriptionAvailableTenants = "";
        if (subscriptionAvailability != null && subscriptionAvailability.equals(APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS)) {
        	subscriptionAvailableTenants = (String) apiData.get("subscriptionTenants", apiData);
        }
        
        String tags = (String) apiData.get("tags", apiData);
        Set<String> tag = new HashSet<String>();
        if (tags != null) {
            if (tags.indexOf(",") >= 0) {
                String[] userTag = tags.split(",");
                tag.addAll(Arrays.asList(userTag).subList(0, tags.split(",").length));
            } else {
                tag.add(tags);
            }
        }
        provider = (provider != null ? provider.trim() : null);
        name = (name != null ? name.trim() : null);
        version = (version != null ? version.trim() : null);
        APIIdentifier oldApiId = new APIIdentifier(provider, name, version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        boolean isTenantFlowStarted = false;
        String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(provider));
        if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
        	isTenantFlowStarted = true;
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
        }

        API oldApi = apiProvider.getAPI(oldApiId);

        String transport = getTransports(apiData);

        String tier = (String) apiData.get("tier", apiData);
        String contextVal = (String) apiData.get("context", apiData);
        String context = contextVal.startsWith("/") ? contextVal : ("/" + contextVal);
        String providerDomain=MultitenantUtils.getTenantDomain(String.valueOf(apiData.get("provider", apiData)));
        if(!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(providerDomain) && !context.contains("/t/"+ providerDomain))
        {
            //Create tenant aware context for API
            context= "/t/"+ providerDomain+context;
        }

        APIIdentifier apiId = new APIIdentifier(provider, name, version);
        API api = new API(apiId);

        NativeArray uriTemplateArr = (NativeArray) apiData.get("uriTemplateArr", apiData);
        NativeArray uriMethodArr = (NativeArray) apiData.get("uriMethodArr", apiData);
        NativeArray authTypeArr = (NativeArray) apiData.get("uriAuthMethodArr", apiData);
        NativeArray throttlingTierArr = (NativeArray) apiData.get("throttlingTierArr", apiData);
        if (uriTemplateArr != null && uriMethodArr != null && authTypeArr != null) {
            if (uriTemplateArr.getLength() == uriMethodArr.getLength()) {
                Set<URITemplate> uriTemplates = new LinkedHashSet<URITemplate>();

                for (int i = 0; i < uriTemplateArr.getLength(); i++) {

                    String uriMethods = (String) uriMethodArr.get(i, uriMethodArr);
                    String[] uriMethodArray = uriMethods.split(",");
                    String uriMethodsThrottlingTiers = (String) throttlingTierArr.get(i, throttlingTierArr);
                    String[] throttlingTierArray = uriMethodsThrottlingTiers.split(",");
                    String uriAuthTypes = (String) authTypeArr.get(i, authTypeArr);
                    String[] uriAuthTypeArray = uriAuthTypes.split(",");
                    for (int k = 0; k < uriMethodArray.length; k++) {
                        for (int j = 0; j < uriAuthTypeArray.length; j++) {
                            if (j == k) {
                                URITemplate uriTemplate = new URITemplate();
                                String templateVal = (String) uriTemplateArr.get(i, uriTemplateArr);
                                String template = templateVal.startsWith("/") ? templateVal : ("/" + templateVal);
                                uriTemplate.setUriTemplate(template);
                                String throttlingTier = throttlingTierArray[j];
                                uriTemplate.setHTTPVerb(uriMethodArray[k]);
                                String authType = uriAuthTypeArray[j];
                                if (authType.equals("Application & Application User")) {
                                    authType = APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN;
                                }
                                if (authType.equals("Application User")) {
                                    authType = "Application_User";
                                }
                                uriTemplate.setThrottlingTier(throttlingTier);
                                uriTemplate.setAuthType(authType);
                                uriTemplate.setResourceURI(endpoint);
                                uriTemplate.setResourceSandboxURI(sandboxUrl);
                                uriTemplates.add(uriTemplate);
                                break;
                            }

                        }
                    }

                }
                api.setUriTemplates(uriTemplates);
            }
        }
        api.setDescription(StringEscapeUtils.escapeHtml(description));
        api.setLastUpdated(new Date());
        api.setUrl(endpoint);
        api.setSandboxUrl(sandboxUrl);
        api.addTags(tag);
        api.setContext(context);
        api.setVisibility(visibility);
        api.setVisibleRoles(visibleRoles != null ? visibleRoles.trim() : null);
        api.setVisibleTenants(visibleTenants != null ? visibleTenants.trim() : null);
        Set<Tier> availableTier = new HashSet<Tier>();
        if (tier != null) {
            String[] tierNames = tier.split(",");
            for (String tierName : tierNames) {
                availableTier.add(new Tier(tierName));
            }
            api.addAvailableTiers(availableTier);
        }
        api.setStatus(oldApi.getStatus());
        api.setWsdlUrl(wsdl);
        api.setWadlUrl(wadl);
        api.setLastUpdated(new Date());
        api.setBusinessOwner(bizOwner);
        api.setBusinessOwnerEmail(bizOwnerEmail);
        api.setTechnicalOwner(techOwner);
        api.setTechnicalOwnerEmail(techOwnerEmail);
        api.setTransports(transport);
        if(!"none".equals(inSequence)){
            api.setInSequence(inSequence);
        }
        if(!"none".equals(outSequence)){
            api.setOutSequence(outSequence);
        }
        api.setOldInSequence(oldApi.getInSequence());
        api.setOldOutSequence(oldApi.getOutSequence());
        api.setRedirectURL(redirectURL);
        api.setApiOwner(apiOwner);
        api.setAdvertiseOnly(advertiseOnly);

        // @todo needs to be validated
        api.setEndpointConfig((String) apiData.get("endpoint_config", apiData));
        
        api.setSubscriptionAvailability(subscriptionAvailability);
        api.setSubscriptionAvailableTenants(subscriptionAvailableTenants);
        api.setResponseCache(responseCache);
        api.setCacheTimeout(cacheTimeOut);
        
        //set secured endpoint parameters
        if ("secured".equals(endpointSecured)) {
            api.setEndpointSecured(true);
            api.setEndpointUTUsername(endpointUTUsername);
            api.setEndpointUTPassword(endpointUTPassword);
        }

        try {
            checkFileSize(fileHostObject);

            if (fileHostObject != null && fileHostObject.getJavaScriptFile().getLength() != 0) {
                Icon icon = new Icon(fileHostObject.getInputStream(),
                                     fileHostObject.getJavaScriptFile().getContentType());
                String thumbPath = APIUtil.getIconPath(apiId);



                    String thumbnailUrl = apiProvider.addIcon(thumbPath, icon);
                    api.setThumbnailUrl(APIUtil.prependTenantPrefix(thumbnailUrl, provider));


                /*Set permissions to anonymous role for thumbPath*/
                APIUtil.setResourcePermissions(api.getId().getProviderName(), null, null, thumbPath);
            } else if (oldApi.getThumbnailUrl() != null) {
                // retain the previously uploaded image
                api.setThumbnailUrl(oldApi.getThumbnailUrl());
            }
            apiProvider.updateAPI(api);
            boolean hasAPIUpdated=false;
            if(!oldApi.equals(api)){
            hasAPIUpdated=true;
            }

            success = true;
        } catch (Exception e) {
            handleException("Error while updating the API- " + name + "-" + version, e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static boolean jsFunction_updateAPIStatus(Context cx, Scriptable thisObj,
                                                     Object[] args,
                                                     Function funObj)
            throws APIManagementException {
        if (args==null || args.length == 0) {
            handleException("Invalid number of input parameters.");
        }

        NativeObject apiData = (NativeObject) args[0];
        boolean success = false;
        String provider = (String) apiData.get("provider", apiData);
        String providerTenantMode = (String) apiData.get("provider", apiData);
        provider=APIUtil.replaceEmailDomain(provider);
        String name = (String) apiData.get("apiName", apiData);
        String version = (String) apiData.get("version", apiData);
        String status = (String) apiData.get("status", apiData);
        boolean publishToGateway = Boolean.parseBoolean((String) apiData.get("publishToGateway", apiData));
        boolean deprecateOldVersions = Boolean.parseBoolean((String) apiData.get("deprecateOldVersions", apiData));
        boolean makeKeysForwardCompatible = Boolean.parseBoolean((String) apiData.get("makeKeysForwardCompatible", apiData));
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerTenantMode));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
            		isTenantFlowStarted = true;
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            APIProvider apiProvider = getAPIProvider(thisObj);
            APIIdentifier apiId = new APIIdentifier(provider, name, version);
            API api = apiProvider.getAPI(apiId);
            if (api != null) {
                APIStatus oldStatus = api.getStatus();
                APIStatus newStatus = getApiStatus(status);
                String currentUser = ((APIProviderHostObject) thisObj).getUsername();
                apiProvider.changeAPIStatus(api, newStatus, currentUser, publishToGateway);

                if (oldStatus.equals(APIStatus.CREATED) && newStatus.equals(APIStatus.PUBLISHED)) {
                    if (makeKeysForwardCompatible) {
                        apiProvider.makeAPIKeysForwardCompatible(api);
                    }

                    if (deprecateOldVersions) {
                        List<API> apiList = apiProvider.getAPIsByProvider(provider);
                        APIVersionComparator versionComparator = new APIVersionComparator();
                        for (API oldAPI : apiList) {
                            if (oldAPI.getId().getApiName().equals(name) &&
                                versionComparator.compare(oldAPI, api) < 0 &&
                                (oldAPI.getStatus().equals(APIStatus.PUBLISHED))) {
                                apiProvider.changeAPIStatus(oldAPI, APIStatus.DEPRECATED,
                                                            currentUser, publishToGateway);
                            }
                        }
                    }
                }
                success = true;
            } else {
                handleException("Couldn't find an API with the name-" + name + "version-" + version);
            }
        } catch (APIManagementException e) {
            handleException("Error while updating API status", e);
            return false;
        }finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static boolean jsFunction_updateSubscriptionStatus(Context cx, Scriptable thisObj,
                                                              Object[] args,
                                                              Function funObj)
            throws APIManagementException {
        if (args==null ||args.length == 0) {
            handleException("Invalid input parameters.");
        }

        NativeObject apiData = (NativeObject) args[0];
        boolean success = false;
        String provider = (String) apiData.get("provider", apiData);
        String name = (String) apiData.get("name", apiData);
        String version = (String) apiData.get("version", apiData);
        String newStatus = (String) args[1];
        int appId = Integer.parseInt((String) args[2]);

        try {
            APIProvider apiProvider = getAPIProvider(thisObj);
            APIIdentifier apiId = new APIIdentifier(provider, name, version);
            apiProvider.updateSubscription(apiId, newStatus, appId);
            return true;

        } catch (APIManagementException e) {
            handleException("Error while updating subscription status", e);
            return false;
        }

    }

    private static void checkFileSize(FileHostObject fileHostObject)
            throws ScriptException, APIManagementException {
        if (fileHostObject != null) {
            long length = fileHostObject.getJavaScriptFile().getLength();
            if (length / 1024.0 > 1024) {
                handleException("Image file exceeds the maximum limit of 1MB");
            }
        }
    }
    
    public static boolean jsFunction_updateTierPermissions(Context cx, Scriptable thisObj,
            Object[] args,
            Function funObj)
            		throws APIManagementException {
    	if (args == null ||args.length == 0) {
    		handleException("Invalid input parameters.");
    	}

    	NativeObject tierData = (NativeObject) args[0];
    	boolean success = false;
    	String tierName = (String) tierData.get("tierName", tierData);
    	String permissiontype = (String) tierData.get("permissiontype", tierData);
    	String roles = (String) tierData.get("roles", tierData);
    	
    	try {
    		APIProvider apiProvider = getAPIProvider(thisObj);
    		apiProvider.updateTierPermissions(tierName, permissiontype, roles);
    		return true;

    	} catch (APIManagementException e) {
    		handleException("Error while updating subscription status", e);
    		return false;
    	}

    }
    
    public static NativeArray jsFunction_getTierPermissions(Context cx, Scriptable thisObj,
            Object[] args,
            Function funObj) {
    	 NativeArray myn = new NativeArray(0);
         APIProvider apiProvider = getAPIProvider(thisObj);
         /* Create an array with everyone role */
         String everyOneRoleName = ServiceReferenceHolder.getInstance().getRealmService().
			 		getBootstrapRealmConfiguration().getEveryOneRoleName();
         String defaultRoleArray[] = new String[1];
         defaultRoleArray[0] = everyOneRoleName;
         try {
        	 Set<Tier> tiers = apiProvider.getTiers();
             Set<TierPermissionDTO> tierPermissions = apiProvider.getTierPermissions();
             int i = 0;
             if (tiers != null) {
            	 
            	 for (Tier tier: tiers) {
            		 NativeObject row = new NativeObject();
            		 boolean found = false;
            		 for (TierPermissionDTO permission : tierPermissions) {
            			 if (permission.getTierName().equals(tier.getName())) {
            				 row.put("tierName", row, permission.getTierName());
            				 row.put("tierDisplayName", row, tier.getDisplayName());
                             row.put("permissionType", row,
                            		 permission.getPermissionType());
                             String[] roles = permission.getRoles();
                             /*If no roles defined return default role list*/
                             if (roles == null ||  roles.length == 0) {
                            	 row.put("roles", row, defaultRoleArray);
                             } else {
                            	 row.put("roles", row,
                            		 permission.getRoles());
                             }
            				 found = true;
            				 break;
            			 }
            		 }
            		 /* If no permissions has defined for this tier*/
            		 if (!found) {
            			 row.put("tierName", row, tier.getName());
            			 row.put("tierDisplayName", row, tier.getDisplayName());
                         row.put("permissionType", row,
                        		 APIConstants.TIER_PERMISSION_ALLOW);
                         row.put("roles", row, defaultRoleArray);
                     }
            		 myn.put(i, myn, row);
                     i++;
            	 } 
             }
         } catch (Exception e) {
             log.error("Error while getting available tiers", e);
         }
         return myn;
    }

    /**
     * This method is to functionality of getting an existing API to API-Provider based
     *
     * @param cx      Rhino context
     * @param thisObj Scriptable object
     * @param args    Passing arguments
     * @param funObj  Function object
     * @return a native array
     * @throws APIManagementException Wrapped exception by org.wso2.carbon.apimgt.api.APIManagementException
     */

    public static NativeArray jsFunction_getAPI(Context cx, Scriptable thisObj,
                                                Object[] args,
                                                Function funObj) throws APIManagementException {
        NativeArray myn = new NativeArray(0);

        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        String providerName = args[0].toString();
        String providerNameTenantFlow = args[0].toString();
        providerName=APIUtil.replaceEmailDomain(providerName);
        String apiName = args[1].toString();
        String version = args[2].toString();

        APIIdentifier apiId = new APIIdentifier(providerName, apiName, version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerNameTenantFlow));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            API api = apiProvider.getAPI(apiId);
            if (api != null) {
                Set<URITemplate> uriTemplates = api.getUriTemplates();
                myn.put(0, myn, checkValue(api.getId().getApiName()));
                myn.put(1, myn, checkValue(api.getDescription()));
                myn.put(2, myn, checkValue(api.getUrl()));
                myn.put(3, myn, checkValue(api.getWsdlUrl()));
                myn.put(4, myn, checkValue(api.getId().getVersion()));
                StringBuilder tagsSet = new StringBuilder("");
                for (int k = 0; k < api.getTags().toArray().length; k++) {
                    tagsSet.append(api.getTags().toArray()[k].toString());
                    if (k != api.getTags().toArray().length - 1) {
                        tagsSet.append(",");
                    }
                }
                myn.put(5, myn, checkValue(tagsSet.toString()));
                StringBuilder tiersSet = new StringBuilder("");
                StringBuilder tiersDisplayNamesSet = new StringBuilder("");
                StringBuilder tiersDescSet = new StringBuilder("");
                Set<Tier> tierSet = api.getAvailableTiers();
                Iterator it = tierSet.iterator();
                int j = 0;
                while (it.hasNext()) {
                    Object tierObject = it.next();
                    Tier tier = (Tier) tierObject;
                    tiersSet.append(tier.getName());
                    tiersDisplayNamesSet.append(tier.getDisplayName());
                    tiersDescSet.append(tier.getDescription());
                    if (j != tierSet.size() - 1) {
                        tiersSet.append(",");
                        tiersDisplayNamesSet.append(",");
                        tiersDescSet.append(",");
                    }
                    j++;
                }

                myn.put(6, myn, checkValue(tiersSet.toString()));
                myn.put(7, myn, checkValue(api.getStatus().toString()));
                myn.put(8, myn, getWebContextRoot(api.getThumbnailUrl()));
                myn.put(9, myn, api.getContext());
                myn.put(10, myn, checkValue(Long.valueOf(api.getLastUpdated().getTime()).toString()));
                myn.put(11, myn, getSubscriberCount(apiId, thisObj));

                if (uriTemplates.size() != 0) {
                    NativeArray uriTempArr = new NativeArray(uriTemplates.size());
                    Iterator i = uriTemplates.iterator();
                    List<NativeArray> uriTemplatesArr = new ArrayList<NativeArray>();
                    while (i.hasNext()) {
                        List<String> utArr = new ArrayList<String>();
                        URITemplate ut = (URITemplate) i.next();
                        utArr.add(ut.getUriTemplate());
                        utArr.add(ut.getMethodsAsString().replaceAll("\\s", ","));
                        utArr.add(ut.getAuthTypeAsString().replaceAll("\\s", ","));
                        utArr.add(ut.getThrottlingTiersAsString().replaceAll("\\s", ","));
                        NativeArray utNArr = new NativeArray(utArr.size());
                        for (int p = 0; p < utArr.size(); p++) {
                            utNArr.put(p, utNArr, utArr.get(p));
                        }
                        uriTemplatesArr.add(utNArr);
                    }

                    for (int c = 0; c < uriTemplatesArr.size(); c++) {
                        uriTempArr.put(c, uriTempArr, uriTemplatesArr.get(c));
                    }

                    myn.put(12, myn, uriTempArr);
                }

                myn.put(13, myn, checkValue(api.getSandboxUrl()));
                myn.put(14, myn, checkValue(tiersDescSet.toString()));
                myn.put(15, myn, checkValue(api.getBusinessOwner()));
                myn.put(16, myn, checkValue(api.getBusinessOwnerEmail()));
                myn.put(17, myn, checkValue(api.getTechnicalOwner()));
                myn.put(18, myn, checkValue(api.getTechnicalOwnerEmail()));
                myn.put(19, myn, checkValue(api.getWadlUrl()));
                myn.put(20, myn, checkValue(api.getVisibility()));
                myn.put(21, myn, checkValue(api.getVisibleRoles()));
                myn.put(22, myn, checkValue(api.getVisibleTenants()));
                myn.put(23, myn, checkValue(api.getEndpointUTUsername()));
                myn.put(24, myn, checkValue(api.getEndpointUTPassword()));
                myn.put(25, myn, checkValue(Boolean.toString(api.isEndpointSecured())));
                myn.put(26, myn, APIUtil.replaceEmailDomainBack(checkValue(api.getId().getProviderName())));
                myn.put(27, myn, checkTransport("http",api.getTransports()));
                myn.put(28, myn, checkTransport("https",api.getTransports()));
                Set<APIStore> storesSet=apiProvider.getExternalAPIStores(api.getId());
                if(storesSet!=null && storesSet.size()!=0){
                    NativeArray apiStoresArray=new NativeArray(0);
                    int i=0;
                    for(APIStore store:storesSet){
                        NativeObject storeObject=new NativeObject();
                        storeObject.put("name",storeObject,store.getName());
                        storeObject.put("displayName",storeObject,store.getDisplayName());
                        storeObject.put("published",storeObject,store.isPublished());
                        apiStoresArray.put(i,apiStoresArray,storeObject);
                        i++;
                    }
                    myn.put(29, myn, apiStoresArray);
                }
                myn.put(30, myn, checkValue(api.getInSequence()));
                myn.put(31, myn, checkValue(api.getOutSequence()));
                
                myn.put(32, myn, checkValue(api.getSubscriptionAvailability()));
                myn.put(33, myn, checkValue(api.getSubscriptionAvailableTenants()));

                //@todo need to handle backword compatibility
                myn.put(34, myn, checkValue(api.getEndpointConfig()));
                
                myn.put(35, myn, checkValue(api.getResponseCache()));
                myn.put(36, myn, checkValue(Integer.toString(api.getCacheTimeout())));
                myn.put(37, myn, checkValue(tiersDisplayNamesSet.toString()));
            } else {
                handleException("Cannot find the requested API- " + apiName +
                                "-" + version);
            }
        } catch (Exception e) {
            handleException("Error occurred while getting API information of the api- " + apiName +
                            "-" + version, e);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return myn;
    }

    public static NativeArray jsFunction_getSubscriberCountByAPIs(Context cx, Scriptable thisObj,
                                                                  Object[] args,
                                                                  Function funObj)
            throws APIManagementException {
        NativeArray myn = new NativeArray(0);
        String providerName = null;
        APIProvider apiProvider = getAPIProvider(thisObj);
        if (args == null ||  args.length==0) {
            handleException("Invalid input parameters.");
        }
        boolean isTenantFlowStarted = false;
        try {
            providerName = APIUtil.replaceEmailDomain((String) args[0]);
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
            		isTenantFlowStarted = true;
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            
            if (providerName != null) {
                List<API> apiSet;
                if (providerName.equals("__all_providers__")) {
                    apiSet = apiProvider.getAllAPIs();
                } else {
                    apiSet = apiProvider.getAPIsByProvider(APIUtil.replaceEmailDomain(providerName));
                }

                Map<String, Long> subscriptions = new TreeMap<String, Long>();
                for (API api : apiSet) {
                    if (api.getStatus() == APIStatus.CREATED) {
                        continue;
                    }
                    long count = apiProvider.getAPISubscriptionCountByAPI(api.getId());
                    if (count == 0) {
                        continue;
                    }

                    String key = api.getId().getApiName() + " (" + api.getId().getProviderName() + ")";
                    Long currentCount = subscriptions.get(key);
                    if (currentCount != null) {
                        subscriptions.put(key, currentCount + count);
                    } else {
                        subscriptions.put(key, count);
                    }
                }

                List<APISubscription> subscriptionData = new ArrayList<APISubscription>();
                for (Map.Entry<String, Long> entry : subscriptions.entrySet()) {
                    APISubscription sub = new APISubscription();
                    sub.name = entry.getKey();
                    sub.count = entry.getValue();
                    subscriptionData.add(sub);
                }
                Collections.sort(subscriptionData, new Comparator<APISubscription>() {
                    public int compare(APISubscription o1, APISubscription o2) {
                        // Note that o2 appears before o1
                        // This is because we need to sort in the descending order
                        return (int) (o2.count - o1.count);
                    }
                });
                if (subscriptionData.size() > 10) {
                    APISubscription other = new APISubscription();        
                    other.name = "[Other]";
                    for (int i = 10; i < subscriptionData.size(); i++) {
                        other.count = other.count + subscriptionData.get(i).count;
                    }
                    while (subscriptionData.size() > 10) {
                        subscriptionData.remove(10);
                    }
                    subscriptionData.add(other);
                }

                int i = 0;
                for (APISubscription sub : subscriptionData) {
                    NativeObject row = new NativeObject();
                    row.put("apiName", row, sub.name);
                    row.put("count", row, sub.count);
                    myn.put(i, myn, row);
                    i++;
                }
            }
        } catch (Exception e) {
            handleException("Error while getting subscribers of the provider: " + providerName, e);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return myn;
    }

    public static NativeArray jsFunction_getTiers(Context cx, Scriptable thisObj,
                                                  Object[] args,
                                                  Function funObj) {
        NativeArray myn = new NativeArray(1);
        APIProvider apiProvider = getAPIProvider(thisObj);
        try {
            Set<Tier> tiers = apiProvider.getTiers();
            int i = 0;
            if (tiers != null) {
                for (Tier tier : tiers) {
                    NativeObject row = new NativeObject();
                    row.put("tierName", row, tier.getName());
                    row.put("tierDisplayName", row, tier.getDisplayName());
                    row.put("tierDescription", row,
                            tier.getDescription() != null ? tier.getDescription() : "");
                    myn.put(i, myn, row);
                    i++;
                }
            }
        } catch (Exception e) {
            log.error("Error while getting available tiers", e);
        }
        return myn;
    }

    public static NativeArray jsFunction_getSubscriberCountByAPIVersions(Context cx,
                                                                         Scriptable thisObj,
                                                                         Object[] args,
                                                                         Function funObj)
            throws APIManagementException {
        NativeArray myn = new NativeArray(0);
        String providerName = null;
        String apiName = null;
        APIProvider apiProvider = getAPIProvider(thisObj);
        if (args == null || args.length==0) {
            handleException("Invalid input parameters.");
        }
        boolean isTenantFlowStarted = false;
        try {
            providerName = APIUtil.replaceEmailDomain((String) args[0]);
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            		isTenantFlowStarted = true;
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            apiName = (String) args[1];
            if (providerName != null && apiName != null) {
                Map<String, Long> subscriptions = new TreeMap<String, Long>();
                Set<String> versions = apiProvider.getAPIVersions(APIUtil.replaceEmailDomain(providerName), apiName);
                for (String version : versions) {
                    APIIdentifier id = new APIIdentifier(providerName, apiName, version);
                    API api = apiProvider.getAPI(id);
                    if (api.getStatus() == APIStatus.CREATED) {
                        continue;
                    }
                    long count = apiProvider.getAPISubscriptionCountByAPI(api.getId());
                    if (count == 0) {
                        continue;
                    }
                    subscriptions.put(api.getId().getVersion(), count);
                }

                int i = 0;
                for (Map.Entry<String, Long> entry : subscriptions.entrySet()) {
                    NativeObject row = new NativeObject();
                    row.put("apiVersion", row, entry.getKey());
                    row.put("count", row, entry.getValue().longValue());
                    myn.put(i, myn, row);
                    i++;
                }
            }
        } catch (Exception e) {
            log.error("Error while getting subscribers of the " +
                      "provider: " + providerName + " and API: " + apiName, e);
        }finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return myn;
    }

    private static int getSubscriberCount(APIIdentifier apiId, Scriptable thisObj)
            throws APIManagementException {
        APIProvider apiProvider = getAPIProvider(thisObj);
        Set<Subscriber> subs = apiProvider.getSubscribersOfAPI(apiId);
        Set<String> subscriberNames = new HashSet<String>();
        if (subs != null) {
            for (Subscriber sub : subs) {
                subscriberNames.add(sub.getName());
            }
            return subscriberNames.size();
        } else {
            return 0;
        }
    }

    private static String checkTransport(String compare, String transport)
            throws APIManagementException {
        if(transport!=null){
            List<String> transportList = new ArrayList<String>();
            transportList.addAll(Arrays.asList(transport.split(",")));
            if(transportList.contains(compare)){
                return "checked";
            }else{
                return "";
            }

        }else{
            return "";
        }
    }

    /**
     * This method is to functionality of getting all the APIs stored
     *
     * @param cx      Rhino context
     * @param thisObj Scriptable object
     * @param args    Passing arguments
     * @param funObj  Function object
     * @return a native array
     * @throws APIManagementException Wrapped exception by org.wso2.carbon.apimgt.api.APIManagementException
     */
    public static NativeArray jsFunction_getAllAPIs(Context cx, Scriptable thisObj,
                                                    Object[] args,
                                                    Function funObj)
            throws APIManagementException {
        NativeArray myn = new NativeArray(0);
        APIProvider apiProvider = getAPIProvider(thisObj);
        /*String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
        if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
        }*/
        try {
            List<API> apiList = apiProvider.getAllAPIs();
            if (apiList != null) {
                Iterator it = apiList.iterator();
                int i = 0;
                while (it.hasNext()) {
                    NativeObject row = new NativeObject();
                    Object apiObject = it.next();
                    API api = (API) apiObject;
                    APIIdentifier apiIdentifier = api.getId();
                    row.put("name", row, apiIdentifier.getApiName());
                    row.put("version", row, apiIdentifier.getVersion());
                    row.put("provider", row, APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                    row.put("status", row, checkValue(api.getStatus().toString()));
                    row.put("thumb", row, getWebContextRoot(api.getThumbnailUrl()));
                    row.put("subs", row, getSubscriberCount(apiIdentifier, thisObj));
                    myn.put(i, myn, row);
                    i++;

                }
            }
        } catch (Exception e) {
            handleException("Error occurred while getting the APIs", e);
        }
        return myn;
    }

    /**
     * This method is to functionality of getting all the APIs stored per provider
     *
     * @param cx      Rhino context
     * @param thisObj Scriptable object
     * @param args    Passing arguments
     * @param funObj  Function object
     * @return a native array
     * @throws APIManagementException Wrapped exception by org.wso2.carbon.apimgt.api.APIManagementException
     */
    public static NativeArray jsFunction_getAPIsByProvider(Context cx, Scriptable thisObj,
                                                           Object[] args,
                                                           Function funObj)
            throws APIManagementException {
        NativeArray myn = new NativeArray(0);
        if (args==null ||args.length == 0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        if (providerName != null) {
        	APIProvider apiProvider = getAPIProvider(thisObj);
        	boolean isTenantFlowStarted = false;
            try {
            	String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
                if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                		isTenantFlowStarted = true;
                        PrivilegedCarbonContext.startTenantFlow();
                        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
                }
                List<API> apiList = apiProvider.getAPIsByProvider(APIUtil.replaceEmailDomain(providerName));
                if (apiList != null) {
                    Iterator it = apiList.iterator();
                    int i = 0;
                    while (it.hasNext()) {
                        NativeObject row = new NativeObject();
                        Object apiObject = it.next();
                        API api = (API) apiObject;
                        APIIdentifier apiIdentifier = api.getId();
                        row.put("name", row, apiIdentifier.getApiName());
                        row.put("version", row, apiIdentifier.getVersion());
                        row.put("provider", row, APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                        row.put("lastUpdatedDate", row, api.getLastUpdated().toString());
                        myn.put(i, myn, row);
                        i++;
                    }
                }
            } catch (Exception e) {
                handleException("Error occurred while getting APIs for " +
                                "the provider: " + providerName, e);
            } finally {
            	if (isTenantFlowStarted) {
            		PrivilegedCarbonContext.endTenantFlow();
            	}
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getSubscribedAPIs(Context cx, Scriptable thisObj,
                                                           Object[] args,
                                                           Function funObj)
            throws APIManagementException {
        String userName = null;
        NativeArray myn = new NativeArray(0);
        APIProvider apiProvider = getAPIProvider(thisObj);

        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        try {
            userName = (String) args[0];
            Subscriber subscriber = new Subscriber(userName);
            Set<API> apiSet = apiProvider.getSubscriberAPIs(subscriber);
            if (apiSet != null) {
                Iterator it = apiSet.iterator();
                int i = 0;
                while (it.hasNext()) {
                    NativeObject row = new NativeObject();
                    Object apiObject = it.next();
                    API api = (API) apiObject;
                    APIIdentifier apiIdentifier = api.getId();
                    row.put("apiName", row, apiIdentifier.getApiName());
                    row.put("version", row, apiIdentifier.getVersion());
                    row.put("provider", row,APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                    row.put("updatedDate", row, api.getLastUpdated().toString());
                    myn.put(i, myn, row);
                    i++;
                }
            }
        } catch (Exception e) {
            handleException("Error occurred while getting the subscribed APIs information " +
                            "for the subscriber-" + userName, e);
        }
        return myn;
    }

    public static NativeArray jsFunction_getAllAPIUsageByProvider(Context cx, Scriptable thisObj,
                                                                  Object[] args, Function funObj)
            throws APIManagementException {

        NativeArray myn = new NativeArray(0);
        String providerName = null;
        APIProvider apiProvider = getAPIProvider(thisObj);

        if (args == null || !isStringValues(args)) {
            handleException("Invalid input parameters.");
        }
        try {
            providerName = (String) args[0];
            if (providerName != null) {
                UserApplicationAPIUsage[] apiUsages = apiProvider.getAllAPIUsageByProvider(providerName);
                for (int i = 0; i < apiUsages.length; i++) {
                    NativeObject row = new NativeObject();
                    row.put("userName", row, apiUsages[i].getUserId());
                    row.put("application", row, apiUsages[i].getApplicationName());
                    row.put("appId", row, "" + apiUsages[i].getAppId());
                    row.put("token", row, apiUsages[i].getAccessToken());
                    row.put("tokenStatus", row, apiUsages[i].getAccessTokenStatus());
                    row.put("subStatus", row, apiUsages[i].getSubStatus());

                    StringBuilder apiSet = new StringBuilder("");
                    for (int k = 0; k < apiUsages[i].getApiSubscriptions().length; k++) {
                        apiSet.append(apiUsages[i].getApiSubscriptions()[k].getSubStatus());
                        apiSet.append("::");
                        apiSet.append(apiUsages[i].getApiSubscriptions()[k].getApiId().getApiName());
                        apiSet.append("::");
                        apiSet.append(apiUsages[i].getApiSubscriptions()[k].getApiId().getVersion());
                        if (k != apiUsages[i].getApiSubscriptions().length - 1) {
                            apiSet.append(",");
                        }
                    }
                    row.put("apis", row, apiSet.toString());
                    myn.put(i, myn, row);
                }
            }
        } catch (Exception e) {
            handleException("Error occurred while getting subscribers of the provider: " + providerName, e);
        }
        return myn;
    }

    public static NativeArray jsFunction_getAllDocumentation(Context cx, Scriptable thisObj,
                                                             Object[] args, Function funObj)
            throws APIManagementException {
        String apiName = null;
        String version = null;
        String providerName;
        NativeArray myn = new NativeArray(0);
        APIProvider apiProvider = getAPIProvider(thisObj);
        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        boolean isTenantFlowStarted = false;
        try {
            providerName = (String) args[0];
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            apiName = (String) args[1];
            version = (String) args[2];
            APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);

            List<Documentation> docsList = apiProvider.getAllDocumentation(apiId);
            Iterator it = docsList.iterator();
            int i = 0;
            while (it.hasNext()) {

                NativeObject row = new NativeObject();
                Object docsObject = it.next();
                Documentation doc = (Documentation) docsObject;
                Object objectSourceType = doc.getSourceType();
                String strSourceType = objectSourceType.toString();
                row.put("docName", row, doc.getName());
                row.put("docType", row, doc.getType().getType());
                row.put("sourceType", row, strSourceType);
                row.put("docLastUpdated", row, (Long.valueOf(doc.getLastUpdated().getTime()).toString()));
                //row.put("sourceType", row, doc.getSourceType());
                if (Documentation.DocumentSourceType.URL.equals(doc.getSourceType())) {
                    row.put("sourceUrl", row, doc.getSourceUrl());
                }

                if (Documentation.DocumentSourceType.FILE.equals(doc.getSourceType())) {
                    row.put("filePath", row, doc.getFilePath());
                }

                if (doc.getType() == DocumentationType.OTHER) {
                    row.put("otherTypeName", row, doc.getOtherTypeName());
                }

                row.put("summary", row, doc.getSummary());
                myn.put(i, myn, row);
                i++;

            }

        } catch (Exception e) {
            handleException("Error occurred while getting documentation of the api - " +
                            apiName + "-" + version, e);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return myn;
    }

    public static NativeArray jsFunction_getInlineContent(Context cx,
                                                          Scriptable thisObj, Object[] args,
                                                          Function funObj)
            throws APIManagementException {
        String apiName;
        String version;
        String providerName;
        String docName;
        String content;
        NativeArray myn = new NativeArray(0);

        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        providerName = (String) args[0];
        apiName = (String) args[1];
        version = (String) args[2];
        docName = (String) args[3];
        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        
        boolean isTenantFlowStarted = false;
        
        try {
        	String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
            		isTenantFlowStarted = true; 
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            
            content = apiProvider.getDocumentationContent(apiId, docName);
        } catch (Exception e) {
            handleException("Error while getting Inline Document Content ", e);
            return null;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        NativeObject row = new NativeObject();
        row.put("providerName", row,APIUtil.replaceEmailDomainBack(providerName));
        row.put("apiName", row, apiName);
        row.put("apiVersion", row, version);
        row.put("docName", row, docName);
        row.put("content", row, content);
        myn.put(0, myn, row);
        return myn;
    }

    public static void jsFunction_addInlineContent(Context cx,
                                                   Scriptable thisObj, Object[] args,
                                                   Function funObj)
            throws APIManagementException {
        String apiName;
        String version;
        String providerName;
        String docName;
        String docContent;

        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        providerName = (String) args[0];
        apiName = (String) args[1];
        version = (String) args[2];
        docName = (String) args[3];
        docContent = (String) args[4];
        if (docContent != null) {
            docContent = docContent.replaceAll("\n", "");
        }
        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName,
                                                version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
        boolean isTenantFlowStarted = false;
        if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
        	isTenantFlowStarted = true;
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
        }
        try {
            if (docName.equals(APIConstants.API_DEFINITION_DOC_NAME)) {
                apiProvider.addAPIDefinitionContent(apiId, docName, docContent);
            } else {
                apiProvider.addDocumentationContent(apiId, docName, docContent);
            }
        } catch (APIManagementException e) {
            handleException("Error occurred while adding the content of the documentation- " + docName, e);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
    }

    public static boolean jsFunction_addDocumentation(Context cx, Scriptable thisObj,
                                                      Object[] args, Function funObj)
            throws APIManagementException {
        if (args == null || args.length==0) {
            handleException("Invalid number of parameters or their types.");
        }
        boolean success;
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        String version = (String) args[2];
        String docName = (String) args[3];
        String docType = (String) args[4];
        String summary = (String) args[5];
        String sourceType = (String) args[6];
        FileHostObject fileHostObject = null;
        String sourceURL = null;

        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);
        Documentation doc = new Documentation(getDocType(docType), docName);
        if (doc.getType() == DocumentationType.OTHER) {
            doc.setOtherTypeName(args[9].toString());
        }

        if (sourceType.equalsIgnoreCase(Documentation.DocumentSourceType.URL.toString())) {
            doc.setSourceType(Documentation.DocumentSourceType.URL);
            sourceURL = args[7].toString();
        } else if (sourceType.equalsIgnoreCase(Documentation.DocumentSourceType.FILE.toString())) {
            doc.setSourceType(Documentation.DocumentSourceType.FILE);
            fileHostObject = (FileHostObject) args[8];
        } else {
            doc.setSourceType(Documentation.DocumentSourceType.INLINE);
        }

        doc.setSummary(summary);
        doc.setSourceUrl(sourceURL);
        APIProvider apiProvider = getAPIProvider(thisObj);
        try {

            if (fileHostObject != null && fileHostObject.getJavaScriptFile().getLength() != 0) {
                Icon icon = new Icon(fileHostObject.getInputStream(),
                                     fileHostObject.getJavaScriptFile().getContentType());
                String filePath = APIUtil.getDocumentationFilePath(apiId, fileHostObject.getName());
                API api = apiProvider.getAPI(apiId);
                String apiPath=APIUtil.getAPIPath(apiId);
                String visibleRolesList = api.getVisibleRoles();
                String[] visibleRoles = new String[0];
                if (visibleRolesList != null) {
                    visibleRoles = visibleRolesList.split(",");
                }
                APIUtil.setResourcePermissions(api.getId().getProviderName(),
                                               api.getVisibility(), visibleRoles,apiPath);
                doc.setFilePath(apiProvider.addIcon(filePath, icon));
            }

        } catch (Exception e) {
            handleException("Error while creating an attachment for Document- " + docName + "-" + version, e);
            return false;
        }
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            		isTenantFlowStarted = true;
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
             }
            apiProvider.addDocumentation(apiId, doc);
            success = true;
        } catch (APIManagementException e) {
            handleException("Error occurred while adding the document- " + docName, e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static boolean jsFunction_removeDocumentation(Context cx, Scriptable thisObj,
                                                         Object[] args, Function funObj)
            throws APIManagementException {
        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        boolean success;
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        String version = (String) args[2];
        String docName = (String) args[3];
        String docType = (String) args[4];

        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);

        APIProvider apiProvider = getAPIProvider(thisObj);
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

            apiProvider.removeDocumentation(apiId, docName, docType);
            success = true;
        } catch (APIManagementException e) {
            handleException("Error occurred while removing the document- " + docName +
                            ".", e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static boolean jsFunction_createNewAPIVersion(Context cx, Scriptable thisObj,
                                                         Object[] args, Function funObj)
            throws APIManagementException {

        boolean success;
        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        String version = (String) args[2];
        String newVersion = (String) args[3];

        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);
        API api = new API(apiId);
        APIProvider apiProvider = getAPIProvider(thisObj);
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            apiProvider.createNewAPIVersion(api, newVersion);
            success = true;
        } catch (DuplicateAPIException e) {
            handleException("Error occurred while creating a new API version. A duplicate API " +
                            "already exists by the same name.", e);
            return false;
        } catch (Exception e) {
            handleException("Error occurred while creating a new API version- " + newVersion, e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static NativeArray jsFunction_getSubscribersOfAPI(Context cx, Scriptable thisObj,
                                                             Object[] args, Function funObj)
            throws APIManagementException {
        String apiName;
        String version;
        String providerName;
        NativeArray myn = new NativeArray(0);
        if (args == null || !isStringValues(args)) {
            handleException("Invalid number of parameters or their types.");
        }

        providerName = (String) args[0];
        apiName = (String) args[1];
        version = (String) args[2];

        APIIdentifier apiId = new APIIdentifier(providerName, apiName, version);
        Set<Subscriber> subscribers;
        APIProvider apiProvider = getAPIProvider(thisObj);
        try {
            subscribers = apiProvider.getSubscribersOfAPI(apiId);
            Iterator it = subscribers.iterator();
            int i = 0;
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object subscriberObject = it.next();
                Subscriber user = (Subscriber) subscriberObject;
                row.put("userName", row, user.getName());
                row.put("subscribedDate", row, checkValue(Long.valueOf(user.getSubscribedDate().getTime()).toString()));
                myn.put(i, myn, row);
                i++;
            }

        } catch (APIManagementException e) {
            handleException("Error occurred while getting subscribers of the API- " + apiName +
                            "-" + version, e);
        }
        return myn;
    }

    public static String jsFunction_isContextExist(Context cx, Scriptable thisObj,
                                                   Object[] args, Function funObj)
            throws APIManagementException {
        Boolean contextExist = false;
        if (args != null && isStringValues(args)) {
            String context = (String) args[0];
            String oldContext = (String) args[1];

            if (context.equals(oldContext)) {
                return contextExist.toString();
            }
            APIProvider apiProvider = getAPIProvider(thisObj);
            try {
                contextExist = apiProvider.isContextExist(context);
            } catch (APIManagementException e) {
                handleException("Error from registry while checking the input context is already exist", e);
            }
        } else {
            handleException("Input context value is null");
        }
        return contextExist.toString();
    }

    private static DocumentationType getDocType(String docType) {
        DocumentationType docsType = null;
        for (DocumentationType type : DocumentationType.values()) {
            if (type.getType().equalsIgnoreCase(docType)) {
                docsType = type;
            }
        }
        return docsType;
    }

    private static boolean isStringValues(Object[] args) {
        int i = 0;
        for (Object arg : args) {

            if (!(arg instanceof String)) {
                return false;

            }
            i++;
        }
        return true;
    }

    private static String checkValue(String input) {
        return input != null ? input : "";
    }


    private static APIStatus getApiStatus(String status) {
        APIStatus apiStatus = null;
        for (APIStatus aStatus : APIStatus.values()) {
            if (aStatus.getStatus().equalsIgnoreCase(status)) {
                apiStatus = aStatus;
            }

        }
        return apiStatus;
    }

    public static NativeArray jsFunction_getProviderAPIVersionUsage(Context cx, Scriptable thisObj,
                                                                    Object[] args, Function funObj)
            throws APIManagementException {
        List<APIVersionUsageDTO> list = null;
        if (args == null || args.length==0) {
            handleException("Invalid input parameters.");
        }
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getUsageByAPIVersions(providerName, apiName);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIVersionUsage", e);
        }
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIVersionUsageDTO usage = (APIVersionUsageDTO) usageObject;
                row.put("version", row, usage.getVersion());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getProviderAPIUsage(Context cx, Scriptable thisObj,
                                                             Object[] args, Function funObj)
            throws APIManagementException {

        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            NativeArray myn = new NativeArray(0);
            return myn;
        }

        List<APIUsageDTO> list = null;
        if (args == null ||  args.length==0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getUsageByAPIs(providerName, fromDate, toDate, 10);
        } catch (APIMgtUsageQueryServiceClientException e) {
            handleException("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }
        NativeArray myn = new NativeArray(0);
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIUsageDTO usage = (APIUsageDTO) usageObject;
                row.put("apiName", row, usage.getApiName());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;

            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getProviderAPIUserUsage(Context cx, Scriptable thisObj,
                                                                 Object[] args, Function funObj)
            throws APIManagementException {
        List<PerUserAPIUsageDTO> list = null;
        if (args == null ||  args.length==0) {
            handleException("Invalid number of parameters.");
        }
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getUsageBySubscribers(providerName, apiName, 10);
        } catch (APIMgtUsageQueryServiceClientException e) {
            handleException("Error while invoking APIUsageStatisticsClient for ProviderAPIUserUsage", e);
        }
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                PerUserAPIUsageDTO usage = (PerUserAPIUsageDTO) usageObject;
                row.put("user", row, usage.getUsername());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getAPIUsageByResourcePath(Context cx, Scriptable thisObj,
                                                                   Object[] args, Function funObj)
            throws APIManagementException {
        List<APIResourcePathUsageDTO> list = null;
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        if (args == null ||  args.length==0) {
            handleException("Invalid input parameters.");
        }
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];

        try {
            APIUsageStatisticsClient client =
                    new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getAPIUsageByResourcePath(providerName, fromDate, toDate);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }

        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIResourcePathUsageDTO usage = (APIResourcePathUsageDTO) usageObject;
                row.put("apiName", row, usage.getApiName());
                row.put("version", row, usage.getVersion());
                row.put("method", row, usage.getMethod());
                row.put("context", row, usage.getContext());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getAPIUsageByUser(Context cx, Scriptable thisObj,
                                                           Object[] args, Function funObj)
            throws APIManagementException {
        List<APIUsageByUserDTO> list = null;
        NativeArray myn = new NativeArray(0);
        if(!HostObjectUtils.checkDataPublishingEnabled()){
            return myn;
        }
        if (args.length == 0) {
            handleException("Invalid number of parameters.");
        }
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];

        try {
            APIUsageStatisticsClient client =
                    new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getAPIUsageByUser(providerName,fromDate,toDate);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }

        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIUsageByUserDTO usage = (APIUsageByUserDTO) usageObject;
                row.put("apiName", row, usage.getApiName());
                row.put("version", row, usage.getVersion());
                row.put("userId", row, usage.getUserID());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getProviderAPIVersionUserUsage(Context cx,
                                                                        Scriptable thisObj,
                                                                        Object[] args,
                                                                        Function funObj)
            throws APIManagementException {
        List<PerUserAPIUsageDTO> list = null;
        if (args == null ||  args.length==0) {
            handleException("Invalid number of parameters.");
        }
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        String providerName = (String) args[0];
        String apiName = (String) args[1];
        String version = (String) args[2];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getUsageBySubscribers(providerName, apiName, version, 10);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUserUsage", e);
        }
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                PerUserAPIUsageDTO usage = (PerUserAPIUsageDTO) usageObject;
                row.put("user", row, usage.getUsername());
                row.put("count", row, usage.getCount());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getProviderAPIVersionUserLastAccess(Context cx,
                                                                             Scriptable thisObj,
                                                                             Object[] args,
                                                                             Function funObj)
            throws APIManagementException {
        List<APIVersionLastAccessTimeDTO> list = null;
        if (args == null ||  args.length==0) {
            handleException("Invalid number of parameters.");
        }
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }

        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getLastAccessTimesByAPI(providerName, fromDate, toDate, 10);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIVersionLastAccess", e);
        }
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIVersionLastAccessTimeDTO usage = (APIVersionLastAccessTimeDTO) usageObject;
                row.put("api_name", row, usage.getApiName());
                row.put("api_version", row, usage.getApiVersion());
                row.put("user", row, usage.getUser());
                Date date = new Date(String.valueOf(usage.getLastAccessTime()));
                row.put("lastAccess", row, Long.valueOf(date.getTime()).toString());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getProviderAPIServiceTime(Context cx, Scriptable thisObj,
                                                                   Object[] args, Function funObj)
            throws APIManagementException {
        List<APIResponseTimeDTO> list = null;
        if (args == null ||  args.length==0) {
            handleException("Invalid number of parameters.");
        }
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }

        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];

        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getResponseTimesByAPIs(providerName, fromDate, toDate, 10);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIServiceTime", e);
        }
        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object usageObject = it.next();
                APIResponseTimeDTO usage = (APIResponseTimeDTO) usageObject;
                row.put("apiName", row, usage.getApiName());
                row.put("serviceTime", row, usage.getServiceTime());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_searchAPIs(Context cx, Scriptable thisObj,
                                                    Object[] args,
                                                    Function funObj) throws APIManagementException {
        NativeArray myn = new NativeArray(0);

        if (args == null || args.length==0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        providerName=APIUtil.replaceEmailDomain(providerName);
        String searchValue = (String) args[1];
        String searchTerm;
        String searchType;

        if (searchValue.contains(":")) {
            if (searchValue.split(":").length > 1) {
                searchType = searchValue.split(":")[0];
                searchTerm = searchValue.split(":")[1];
            } else {
                throw new APIManagementException("Search term is missing. Try again with valid search query.");
            }

        } else {
            searchTerm = searchValue;
            searchType = "default";
        }
        try {
            if ("*".equals(searchTerm) || searchTerm.startsWith("*")) {
                searchTerm = searchTerm.replaceFirst("\\*", ".*");
            }
            APIProvider apiProvider = getAPIProvider(thisObj);

            List<API> searchedList = apiProvider.searchAPIs(searchTerm, searchType, providerName);
            Iterator it = searchedList.iterator();
            int i = 0;
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object apiObject = it.next();
                API api = (API) apiObject;
                APIIdentifier apiIdentifier = api.getId();
                row.put("name", row, apiIdentifier.getApiName());
                row.put("provider", row, APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                row.put("version", row, apiIdentifier.getVersion());
                row.put("status", row, checkValue(api.getStatus().toString()));
                row.put("thumb", row, getWebContextRoot(api.getThumbnailUrl()));
                row.put("subs", row, apiProvider.getSubscribersOfAPI(api.getId()).size());
                if (providerName != null) {
                    row.put("lastUpdatedDate", row, checkValue(api.getLastUpdated().toString()));
                }
                myn.put(i, myn, row);
                i++;


            }
        } catch (Exception e) {
            handleException("Error occurred while getting the searched API- " + searchValue, e);
        }
        return myn;
    }


    public static boolean jsFunction_hasCreatePermission(Context cx, Scriptable thisObj,
                                                         Object[] args,
                                                         Function funObj) {
        APIProvider provider = getAPIProvider(thisObj);
        if (provider instanceof UserAwareAPIProvider) {
            try {
                ((UserAwareAPIProvider) provider).checkCreatePermission();
                return true;
            } catch (APIManagementException e) {
                return false;
            }
        }
        return false;
    }
    
    public static boolean jsFunction_hasManageTierPermission(Context cx, Scriptable thisObj,
            Object[] args,
            Function funObj) {
    	APIProvider provider = getAPIProvider(thisObj);
    	if (provider instanceof UserAwareAPIProvider) {
    		try {
    			((UserAwareAPIProvider) provider).checkManageTiersPermission();
    			return true;
    		} catch (APIManagementException e) {
    			return false;
    		}
    	}
    	return false;
    }

    public static boolean jsFunction_hasUserPermissions(Context cx, Scriptable thisObj,
                                                        Object[] args,
                                                        Function funObj)
            throws APIManagementException {
        if (args == null || !isStringValues(args)) {
            handleException("Invalid input parameters.");
        }
        String username = (String) args[0];
        return APIUtil.checkPermissionQuietly(username, APIConstants.Permissions.API_CREATE) ||
               APIUtil.checkPermissionQuietly(username, APIConstants.Permissions.API_PUBLISH);
    }

    public static boolean jsFunction_hasPublishPermission(Context cx, Scriptable thisObj,
                                                          Object[] args,
                                                          Function funObj) {
        APIProvider provider = getAPIProvider(thisObj);
        if (provider instanceof UserAwareAPIProvider) {
            try {
                ((UserAwareAPIProvider) provider).checkPublishPermission();
                return true;
            } catch (APIManagementException e) {
                return false;
            }
        }
        return false;
    }

    public static void jsFunction_loadRegistryOfTenant(Context cx,
                                                       Scriptable thisObj, Object[] args, Function funObj){
        String tenantDomain = args[0].toString();
        if(tenantDomain != null && !org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
            try {
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().
                        getTenantManager().getTenantId(tenantDomain);
                APIUtil.loadTenantRegistry(tenantId);
            } catch (org.wso2.carbon.user.api.UserStoreException e) {
                log.error("Could not load tenant registry. Error while getting tenant id from tenant domain " +
                        tenantDomain);
            }
        }

    }

    public static NativeArray jsFunction_getLifeCycleEvents(Context cx, Scriptable thisObj,
                                                            Object[] args,
                                                            Function funObj)
            throws APIManagementException {
        NativeArray lifeCycles = new NativeArray(0);
        if (args == null) {
            handleException("Invalid input parameters.");
        }
        NativeObject apiData = (NativeObject) args[0];
        String provider = (String) apiData.get("provider", apiData);
        String name = (String) apiData.get("name", apiData);
        String version = (String) apiData.get("version", apiData);
        APIIdentifier apiId = new APIIdentifier(provider, name, version);
        APIProvider apiProvider = getAPIProvider(thisObj);
        try {
            List<LifeCycleEvent> lifeCycleEvents = apiProvider.getLifeCycleEvents(apiId);
            int i = 0;
            if (lifeCycleEvents != null) {
                for (LifeCycleEvent lcEvent : lifeCycleEvents) {
                    NativeObject event = new NativeObject();
                    event.put("username", event, APIUtil.replaceEmailDomainBack(checkValue(lcEvent.getUserId())));
                    event.put("newStatus", event, lcEvent.getNewStatus() != null ? lcEvent.getNewStatus().toString() : "");
                    event.put("oldStatus", event, lcEvent.getOldStatus() != null ? lcEvent.getOldStatus().toString() : "");

                    event.put("date", event, checkValue(Long.valueOf(lcEvent.getDate().getTime()).toString()));
                    lifeCycles.put(i, lifeCycles, event);
                    i++;
                }
            }
        } catch (APIManagementException e) {
            log.error("Error from registry while checking the input context is already exist", e);
        }
        return lifeCycles;
    }

    public static void jsFunction_removeAPI(Context cx, Scriptable thisObj,
                                            Object[] args,
                                            Function funObj)
            throws APIManagementException {
        if (args == null) {
            handleException("Invalid input parameters.");
        }
        NativeObject apiData = (NativeObject) args[0];

        String provider = (String) apiData.get("provider", apiData);
        provider=APIUtil.replaceEmailDomain(provider);
        String name = (String) apiData.get("name", apiData);
        String version = (String) apiData.get("version", apiData);
        APIIdentifier apiId = new APIIdentifier(provider, name, version);
        boolean isTenantFlowStarted = false;
        try{
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(provider));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            APIProvider apiProvider = getAPIProvider(thisObj);
            apiProvider.deleteAPI(apiId);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
    }

    private static class APISubscription {
        private String name;
        private long count;
    }

    public static boolean jsFunction_updateDocumentation(Context cx, Scriptable thisObj,
                                                         Object[] args, Function funObj)
            throws APIManagementException {
        if (args == null || args.length==0) {
            handleException("Invalid number of parameters or their types.");
        }
        boolean success;
        String providerName = (String) args[0];
        providerName=APIUtil.replaceEmailDomain(providerName);
        String apiName = (String) args[1];
        String version = (String) args[2];
        String docName = (String) args[3];
        String docType = (String) args[4];
        String summary = (String) args[5];
        String sourceType = (String) args[6];
        String sourceURL = null;
        FileHostObject fileHostObject = null;

        APIIdentifier apiId = new APIIdentifier(providerName, apiName, version);
        Documentation doc = new Documentation(getDocType(docType), docName);

        if (doc.getType() == DocumentationType.OTHER) {
            doc.setOtherTypeName(args[9].toString());
        }

        if (sourceType.equalsIgnoreCase(Documentation.DocumentSourceType.URL.toString())) {
            doc.setSourceType(Documentation.DocumentSourceType.URL);
            sourceURL = args[7].toString();
        } else if (sourceType.equalsIgnoreCase(Documentation.DocumentSourceType.FILE.toString())) {
            doc.setSourceType(Documentation.DocumentSourceType.FILE);
            fileHostObject = (FileHostObject) args[8];
        } else {
            doc.setSourceType(Documentation.DocumentSourceType.INLINE);
        }
        doc.setSummary(summary);
        doc.setSourceUrl(sourceURL);
        APIProvider apiProvider = getAPIProvider(thisObj);
        Documentation oldDoc = apiProvider.getDocumentation(apiId, doc.getType(), doc.getName());

        try {

            if (fileHostObject != null && fileHostObject.getJavaScriptFile().getLength() != 0) {
                Icon icon = new Icon(fileHostObject.getInputStream(),
                                     fileHostObject.getJavaScriptFile().getContentType());
                String filePath = APIUtil.getDocumentationFilePath(apiId, fileHostObject.getName());
                doc.setFilePath(apiProvider.addIcon(filePath, icon));
            } else if (oldDoc.getFilePath() != null) {
                doc.setFilePath(oldDoc.getFilePath());
            }

        } catch (Exception e) {
            handleException("Error while creating an attachment for Document- " + docName + "-" + version, e);
            return false;
        }
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerName));
            if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            	isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            apiProvider.updateDocumentation(apiId, doc);
            success = true;
        } catch (APIManagementException e) {
            handleException("Error occurred while adding the document- " + docName, e);
            return false;
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return success;
    }

    public static boolean jsFunction_isAPIOlderVersionExist(Context cx, Scriptable thisObj,
                                                            Object[] args, Function funObj)
            throws APIManagementException {
        boolean apiOlderVersionExist = false;
        if (args==null ||args.length == 0) {
            handleException("Invalid number of input parameters.");
        }

        NativeObject apiData = (NativeObject) args[0];
        String provider = (String) apiData.get("provider", apiData);
        provider=APIUtil.replaceEmailDomain(provider);
        String name = (String) apiData.get("name", apiData);
        String currentVersion = (String) apiData.get("version", apiData);
        boolean isTenantFlowStarted = false;
        try {
	        String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(provider));
	        if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
	        		isTenantFlowStarted = true;
	                PrivilegedCarbonContext.startTenantFlow();
	                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
	        }
	        
	        APIProvider apiProvider = getAPIProvider(thisObj);
	        Set<String> versions = apiProvider.getAPIVersions(provider, name);
	        APIVersionStringComparator comparator = new APIVersionStringComparator();
	        for (String version : versions) {
	            if (comparator.compare(version, currentVersion) < 0) {
	                apiOlderVersionExist = true;
	                break;
	            }
	        }
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return apiOlderVersionExist;
    }

    public static String jsFunction_isURLValid(Context cx, Scriptable thisObj,
                                               Object[] args, Function funObj)
            throws APIManagementException {
        String response = "";
        if (args == null || !isStringValues(args)) {
            handleException("Invalid input parameters.");
        }
        String urlVal = (String) args[1];
        String type = (String) args[0];
        if (urlVal != null && !urlVal.equals("")) {
            try {
                if (type != null && type.equals("wsdl")) {
                    validateWsdl(urlVal);
                } else {
                    URL url = new URL(urlVal);
                    URLConnection conn = url.openConnection();
                    conn.connect();
                }
                response = "success";
            } catch (MalformedURLException e) {
                response = "malformed";
            } catch (UnknownHostException e) {
                response = "unknown";
            } catch (ConnectException e) {
                response = "Cannot establish connection to the provided address";
            } catch (SSLHandshakeException e) {
                response = "ssl_error";
            } catch (Exception e) {
                response = e.getMessage();
            }
        }
        return response;

    }

    private boolean resourceMethodMatches(String[] resourceMethod1,
                                          String[] resourceMethod2) {
        for (String m1 : resourceMethod1) {
            for (String m2 : resourceMethod2) {
                if (m1.equals(m2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateWsdl(String url) throws Exception {

        URL wsdl = new URL(url);
        BufferedReader in = new BufferedReader(new InputStreamReader(wsdl.openStream()));
        String inputLine;
        boolean isWsdl2 = false;
        boolean isWsdl10 = false;
        StringBuilder urlContent = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            String wsdl2NameSpace = "http://www.w3.org/ns/wsdl";
            String wsdl10NameSpace = "http://schemas.xmlsoap.org/wsdl/";
            urlContent.append(inputLine);
            isWsdl2 = urlContent.indexOf(wsdl2NameSpace) > 0;
            isWsdl10 = urlContent.indexOf(wsdl10NameSpace) > 0;
        }
        in.close();
        if (isWsdl10) {
            javax.wsdl.xml.WSDLReader wsdlReader11 = javax.wsdl.factory.WSDLFactory.newInstance().newWSDLReader();
            wsdlReader11.readWSDL(url);
        } else if (isWsdl2) {
            WSDLReader wsdlReader20 = WSDLFactory.newInstance().newWSDLReader();
            wsdlReader20.readWSDL(url);
        } else {
            handleException("URL is not in format of wsdl1/wsdl2");
        }

    }

    private static String getWebContextRoot(String postfixUrl) {
        String webContext = CarbonUtils.getServerConfiguration().getFirstProperty("WebContextRoot");
        if (postfixUrl != null && webContext != null && !webContext.equals("/")) {
            postfixUrl = webContext + postfixUrl;
        }
        return postfixUrl;
    }


    public static NativeArray jsFunction_searchAccessTokens(Context cx, Scriptable thisObj,
                                                            Object[] args,
                                                            Function funObj)
            throws Exception {
        NativeObject tokenInfo;
        NativeArray tokenInfoArr = new NativeArray(0);
        if (args == null || !isStringValues(args)) {
            handleException("Invalid input parameters.");
        }
        String searchValue = (String) args[0];
        String searchTerm;
        String searchType;
        APIProvider apiProvider = getAPIProvider(thisObj);
        Map<Integer, APIKey> tokenData = null;
        String loggedInUser = ((APIProviderHostObject) thisObj).getUsername();

        if (searchValue.contains(":")) {
            searchTerm = searchValue.split(":")[1];
            searchType = searchValue.split(":")[0];
            if ("*".equals(searchTerm) || searchTerm.startsWith("*")) {
                searchTerm = searchTerm.replaceFirst("\\*", ".*");
            }
            tokenData = apiProvider.searchAccessToken(searchType, searchTerm, loggedInUser);
        } else {
            //Check whether old access token is already available
            if (apiProvider.isApplicationTokenExists(searchValue)) {
                APIKey tokenDetails = apiProvider.getAccessTokenData(searchValue);
                if (tokenDetails.getAccessToken() == null) {
                    throw new APIManagementException("The requested access token is already revoked or No access token available as per requested.");
                }
                tokenData = new HashMap<Integer, APIKey>();
                tokenData.put(0, tokenDetails);
            } else {
                if ("*".equals(searchValue) || searchValue.startsWith("*")) {
                    searchValue = searchValue.replaceFirst("\\*", ".*");
                }
                tokenData = apiProvider.searchAccessToken(null, searchValue, loggedInUser);
            }
        }
        if (tokenData != null && tokenData.size() != 0) {
            for (int i = 0; i < tokenData.size(); i++) {
                tokenInfo = new NativeObject();
                tokenInfo.put("token", tokenInfo, tokenData.get(i).getAccessToken());
                tokenInfo.put("user", tokenInfo, tokenData.get(i).getAuthUser());
                tokenInfo.put("scope", tokenInfo, tokenData.get(i).getTokenScope());
                tokenInfo.put("createTime", tokenInfo, tokenData.get(i).getCreatedDate());
                if (tokenData.get(i).getValidityPeriod() == Long.MAX_VALUE) {
                    tokenInfo.put("validTime", tokenInfo, "Won't Expire");
                } else {
                    tokenInfo.put("validTime", tokenInfo, tokenData.get(i).getValidityPeriod());
                }
                tokenInfo.put("consumerKey", tokenInfo, tokenData.get(i).getConsumerKey());
                tokenInfoArr.put(i, tokenInfoArr, tokenInfo);
            }
        } else {
            throw new APIManagementException("The requested access token is already revoked or No access token available as per requested.");
        }

        return tokenInfoArr;

    }

    public static void jsFunction_revokeAccessToken(Context cx, Scriptable thisObj,
                                                    Object[] args,
                                                    Function funObj)
            throws Exception {
        if (args == null || !isStringValues(args)) {
            handleException("Invalid input parameters.");
        }
        String accessToken = (String) args[0];
        String consumerKey = (String) args[1];
        String authUser = (String) args[2];
        APIProvider apiProvider = getAPIProvider(thisObj);

        try {
            SubscriberKeyMgtClient keyMgtClient = HostObjectUtils.getKeyManagementClient();
            keyMgtClient.revokeAccessToken(accessToken, consumerKey, authUser);

            Set<APIIdentifier> apiIdentifierSet = apiProvider.getAPIByAccessToken(accessToken);
            List<org.wso2.carbon.apimgt.handlers.security.stub.types.APIKeyMapping> mappings = new ArrayList<org.wso2.carbon.apimgt.handlers.security.stub.types.APIKeyMapping>();
            for (APIIdentifier apiIdentifier : apiIdentifierSet) {
                org.wso2.carbon.apimgt.handlers.security.stub.types.APIKeyMapping mapping = new org.wso2.carbon.apimgt.handlers.security.stub.types.APIKeyMapping();
                API apiDefinition = apiProvider.getAPI(apiIdentifier);
                mapping.setApiVersion(apiIdentifier.getVersion());
                mapping.setContext(apiDefinition.getContext());
                mapping.setKey(accessToken);
                mappings.add(mapping);
            }
            if (mappings.size() > 0) {
                APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                        getAPIManagerConfigurationService().getAPIManagerConfiguration();
                List<Environment> gatewayEnvs = config.getApiGatewayEnvironments();
                for(Environment environment : gatewayEnvs){
                    APIAuthenticationAdminClient client = new APIAuthenticationAdminClient(environment);
                    client.invalidateKeys(mappings);
                }


            }
        } catch (Exception e) {
            handleException("Error while revoking the access token: " + accessToken, e);

        }


    }

    public static NativeArray jsFunction_getAPIResponseFaultCount(Context cx, Scriptable thisObj,
                                                                  Object[] args, Function funObj)
            throws APIManagementException {
        List<APIResponseFaultCountDTO> list = null;
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        if (args == null || args.length==0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        String fromDate = (String) args[1];
        String toDate = (String) args[2];
        try {
            APIUsageStatisticsClient client =
                    new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getAPIResponseFaultCount(providerName,fromDate,toDate);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }

        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object faultObject = it.next();
                APIResponseFaultCountDTO fault = (APIResponseFaultCountDTO) faultObject;
                row.put("apiName", row, fault.getApiName());
                row.put("version", row, fault.getVersion());
                row.put("context", row, fault.getContext());
                row.put("count", row, fault.getCount());
                row.put("faultPercentage", row, fault.getFaultPercentage());
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getAPIFaultyAnalyzeByTime(Context cx, Scriptable thisObj,
                                                                   Object[] args, Function funObj)
            throws APIManagementException {
        List<APIResponseFaultCountDTO> list = null;
        NativeArray myn = new NativeArray(0);
        if (!HostObjectUtils.checkDataPublishingEnabled()) {
            return myn;
        }
        if (args == null || args.length==0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        try {
            APIUsageStatisticsClient client =
                    new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getAPIFaultyAnalyzeByTime(providerName);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }

        Iterator it = null;
        if (list != null) {
            it = list.iterator();
        }
        int i = 0;
        if (it != null) {
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object faultObject = it.next();
                APIResponseFaultCountDTO fault = (APIResponseFaultCountDTO) faultObject;
                long faultTime = Long.parseLong(fault.getRequestTime());
                row.put("apiName", row, fault.getApiName());
                row.put("version", row, fault.getVersion());
                row.put("context", row, fault.getContext());
                row.put("requestTime", row, faultTime);
                myn.put(i, myn, row);
                i++;
            }
        }
        return myn;
    }

    public static NativeArray jsFunction_getFirstAccessTime(Context cx, Scriptable thisObj,
                                                            Object[] args, Function funObj)
            throws APIManagementException {

        if(!HostObjectUtils.checkDataPublishingEnabled()){
            NativeArray myn = new NativeArray(0);
            return myn;
        }

        List<String> list = null;
        if (args.length == 0) {
            handleException("Invalid number of parameters.");
        }
        String providerName = (String) args[0];
        try {
            APIUsageStatisticsClient client = new APIUsageStatisticsClient(((APIProviderHostObject) thisObj).getUsername());
            list = client.getFirstAccessTime(providerName,1);
        } catch (APIMgtUsageQueryServiceClientException e) {
            log.error("Error while invoking APIUsageStatisticsClient for ProviderAPIUsage", e);
        }
        NativeArray myn = new NativeArray(0);
        NativeObject row = new NativeObject();
        row.put("year",row,list.get(0).toString());
        row.put("month",row,list.get(1).toString());
        row.put("day",row,list.get(2).toString());
        myn.put(0,myn,row);
        return myn;
    }

    public static boolean jsFunction_validateRoles(Context cx,
                                                   Scriptable thisObj, Object[] args,
                                                   Function funObj) {
        if (args == null || args.length==0) {
            return false;
        }

        boolean valid=false;
        String inputRolesSet = (String)args[0];
        String username=  (String) args[1];
        String[] inputRoles=null;
        if (inputRolesSet != null) {
            inputRoles = inputRolesSet.split(",");
        }

        try {
            String[] roles=APIUtil.getRoleNames(username);

            if (roles != null && inputRoles != null) {
                for (String inputRole : inputRoles) {
                    for (String role : roles) {
                        valid= (inputRole.equals(role));
                        if(valid){ //If we found a match for the input role,then no need to process the for loop further
                            break;
                        }
                    }
                    //If the input role doesn't match with any of the role existing in the system
                    if(!valid){
                        return valid;
                    }

                }
                return valid;
            }
        }catch (Exception e) {
            log.error("Error while validating the input roles.",e);
        }

        return valid;
    }

    public static NativeArray jsFunction_getExternalAPIStores(Context cx,
                                                              Scriptable thisObj, Object[] args,
                                                              Function funObj)
            throws APIManagementException {
        Set<APIStore> apistoresList = APIUtil.getExternalAPIStores();
        NativeArray myn = new NativeArray(0);
        if (apistoresList == null) {
            return null;
        } else {
            Iterator it = apistoresList.iterator();
            int i = 0;
            while (it.hasNext()) {
                NativeObject row = new NativeObject();
                Object apistoreObject = it.next();
                APIStore apiStore = (APIStore) apistoreObject;
                row.put("displayName", row, apiStore.getDisplayName());
                row.put("name", row, apiStore.getName());
                row.put("endpoint", row, apiStore.getEndpoint());

                myn.put(i, myn, row);
                i++;

            }
            return myn;
        }

    }

    /**
     * Retrieves custom sequences from registry
     * @param cx
     * @param thisObj
     * @param args
     * @param funObj
     * @return
     * @throws APIManagementException
     */
	public static NativeArray jsFunction_getCustomOutSequences(Context cx, Scriptable thisObj,
	                                                        Object[] args, Function funObj)
	                                                                                       throws APIManagementException {
		APIProvider apiProvider = getAPIProvider(thisObj);
		List<String> sequenceList = apiProvider.getCustomOutSequences();

		NativeArray myn = new NativeArray(0);
		if (sequenceList == null) {
			return null;
		} else {
			for (int i = 0; i < sequenceList.size(); i++) {
				myn.put(i, myn, sequenceList.get(i));
			}
			return myn;
		}

	}
	
	/**
     * Retrieves custom sequences from registry
     * @param cx
     * @param thisObj
     * @param args
     * @param funObj
     * @return
     * @throws APIManagementException
     */
	public static NativeArray jsFunction_getCustomInSequences(Context cx, Scriptable thisObj,
	                                                        Object[] args, Function funObj)
	                                                                                       throws APIManagementException {
		APIProvider apiProvider = getAPIProvider(thisObj);
		List<String> sequenceList = apiProvider.getCustomInSequences();

		NativeArray myn = new NativeArray(0);
		if (sequenceList == null) {
			return null;
		} else {
			for (int i = 0; i < sequenceList.size(); i++) {
				myn.put(i, myn, sequenceList.get(i));
			}
			return myn;
		}

	}
	
	public static boolean jsFunction_isSynapseGateway(Context cx, Scriptable thisObj,
            Object[] args,
            Function funObj) throws APIManagementException {
		APIProvider provider = getAPIProvider(thisObj);
		if (!provider.isSynapseGateway()) {
			return false;
		}
		return true;
	}
    public static boolean jsFunction_updateExternalAPIStores(Context cx,Scriptable thisObj, Object[] args,
                                                              Function funObj)
                throws APIManagementException {
                boolean updated=false;
                Set<APIStore> apistoresList = APIUtil.getExternalAPIStores();
                NativeObject apiData = (NativeObject) args[0];
                String provider = String.valueOf(apiData.get("provider", apiData));
                if (provider != null) {
                        provider = APIUtil.replaceEmailDomain(provider);
                    }
                String name = (String) apiData.get("apiName", apiData);
                String version = (String) apiData.get("version", apiData);
                APIProvider apiProvider = getAPIProvider(thisObj);

                        APIIdentifier apiId = new APIIdentifier(provider, name, version);
                API api = apiProvider.getAPI(apiId);
                //Getting selected external API stores from UI and publish API to them.
                        NativeArray externalAPIStores = (NativeArray) apiData.get("externalAPIStores", apiData);
                //Check if no external APIStore selected from UI
                        if (externalAPIStores != null && externalAPIStores.getLength() != 0) {
                        Set<APIStore> inputStores = new HashSet<APIStore>();
                        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                                        getAPIManagerConfigurationService().getAPIManagerConfiguration();
                        for (Object store : externalAPIStores) {
                                APIStore apiStore = config.getExternalAPIStore((String) store); //Get full details of Store
                                inputStores.add(apiStore);
                            }
                        updated=apiProvider.updateAPIsInExternalAPIStores(api,inputStores);
                    }
                return updated;
            }

}






/*
 * Copyright (c) 2005-2012, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.interceptor.valve;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.Constants;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.core.APIManagerErrorConstants;
import org.wso2.carbon.apimgt.core.APIManagerConstants;
import org.wso2.carbon.apimgt.core.authenticate.APITokenValidator;
import org.wso2.carbon.apimgt.core.gateway.APITokenAuthenticator;
import org.wso2.carbon.apimgt.core.usage.APIStatsPublisher;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.interceptor.valve.internal.DataHolder;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.apimgt.usage.publisher.DataPublisherUtil;
import org.wso2.carbon.apimgt.usage.publisher.internal.UsageComponent;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.tomcat.ext.valves.CarbonTomcatValve;
import org.wso2.carbon.tomcat.ext.valves.CompositeValve;

import javax.cache.Cache;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

/**
 * APIManagerInterceptorValve is exposed as a CarbonTomatValve and it filters 
 * all the requests to published APIs and perform API Management for those APIs.
 *
 */
public class APIManagerInterceptorValve extends CarbonTomcatValve {
	
	private static final Log log = LogFactory.getLog(APIManagerInterceptorValve.class);

    private APIKeyValidationInfoDTO apiKeyValidationDTO;

    private boolean statsPublishingEnabled;

    private String statsPublisherClass;

    private volatile APIMgtUsageDataPublisher publisher;

    private boolean initialized = false;

    private String hostName;

    //Cache contextCache = null;

    APITokenAuthenticator authenticator;

    public APIManagerInterceptorValve () {

        if (!initialized) {
            statsPublishingEnabled = UsageComponent.getApiMgtConfigReaderService().isEnabled();
            statsPublisherClass = UsageComponent.getApiMgtConfigReaderService().getPublisherClass();
            hostName = DataPublisherUtil.getHostAddress();
            //contextCache = APIUtil.getAPIContextCache();
            authenticator = new APITokenAuthenticator();
            initialized = true;
        }

    }

    public void invoke(Request request, Response response, CompositeValve compositeValve) {
        
        String context = request.getContextPath();
        if (context == null || context.equals("")) {
            //Invoke next valve in pipe.
            getNext().invoke(request, response, compositeValve);
            return;
        }

        boolean contextExist;
        Boolean contextValueInCache = null;
        if (APIUtil.getAPIContextCache().get(context) != null) {
            contextValueInCache = Boolean.parseBoolean(APIUtil.getAPIContextCache().get(context).toString());
        }

        if (contextValueInCache != null) {
            contextExist = contextValueInCache;
        } else {
            contextExist = ApiMgtDAO.isContextExist(context);
            APIUtil.getAPIContextCache().put(context, contextExist);
        }

        if (!contextExist) {
            //Invoke next valve in pipe.
            getNext().invoke(request, response, compositeValve);
            return;
        }

        if(request.getMethod().equals(Constants.Configuration.HTTP_METHOD_GET)){
            //TODO:Need to get these paths from a config file.
            if(request.getRequestURI().matches(context+"/[^/]*/services")){
                getNext().invoke(request,response,compositeValve);
                return;
            }
            Enumeration<String> params = request.getParameterNames();
            String paramName = null;
            while (params.hasMoreElements()){
                paramName =params.nextElement();
                if(paramName.endsWith("wsdl") || paramName.endsWith("wadl")){
                    getNext().invoke(request,response,compositeValve);
                    return;
                }
            }
        }
        
        long requestTime = System.currentTimeMillis();

        if (contextExist) {
        	//Use embedded API Management
        	log.debug("API Manager Interceptor Valve Got invoked!!");
            String bearerToken = request.getHeader(APIConstants.OperationParameter.AUTH_PARAM_NAME);
            String accessToken = null;

            /* Authenticate*/
            try {
                if (bearerToken != null) {
                    String[] token = bearerToken.split("Bearer");/*Check a util method*/
                    if (token.length > 1 && token[1] != null) {
                        accessToken = token[1].trim();
                    }
                    else{
                    	//There can be some API published with None Auth Type
                        /*throw new APIFaultException(APIConstants.KeyValidationStatus.API_AUTH_INVALID_CREDENTIALS,
                                "Invalid format for Authorization header. Expected 'Bearer <token>'");*/
                    }
                }

            	String apiVersion = getAPIVersion(request);
                doAuthenticate(context, apiVersion, accessToken,
                		authenticator.getResourceAuthenticationScheme(context, apiVersion, request.getRequestURI(), request.getMethod()),
                request.getHeader(APITokenValidator.getAPIManagerClientDomainHeader()));
            } catch (APIManagementException e) {
                    //ignore
            } catch (APIFaultException e) {/* If !isAuthorized APIFaultException is thrown*/
            	String faultPayload = getFaultPayload(e, APIManagerErrorConstants.API_SECURITY_NS, 
                APIManagerErrorConstants.API_SECURITY_NS_PREFIX).toString();
				handleFailure(response, faultPayload);
				//Invoke next valve in pipe.
                getNext().invoke(request, response, compositeValve);
                return;
			}
            /* Throttle*/
            try {
            	doThrottle(request, accessToken);
			} catch (APIFaultException e) {/* If Throttled Out, APIFaultException is thrown*/
				String faultPayload = getFaultPayload(e, APIManagerErrorConstants.API_THROTTLE_NS, 
                APIManagerErrorConstants.API_THROTTLE_NS_PREFIX).toString();
				handleFailure(response, faultPayload);
				//Invoke next valve in pipe.
                getNext().invoke(request, response, compositeValve);
                return;
			}
            /* Publish Statistic if enabled*/
            if (statsPublishingEnabled) {
            	publishRequestStatistics(request, requestTime);
            }
        }

        //Invoke next valve in pipe.
        getNext().invoke(request, response, compositeValve);

        //Handle Responses
        if (contextExist && statsPublishingEnabled) {
            publishResponseStatistics(request, requestTime);
        }
    }

	private boolean doAuthenticate(String context, String version, String accessToken, String requiredAuthenticationLevel, String clientDomain)
            throws APIManagementException, APIFaultException {
			
			if(APIConstants.AUTH_NO_AUTHENTICATION.equals(requiredAuthenticationLevel)){
				return true;
			}
            APITokenValidator tokenValidator = new APITokenValidator();
            apiKeyValidationDTO = tokenValidator.validateKey(context, version,accessToken, APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN,
                    clientDomain);
            if (apiKeyValidationDTO.isAuthorized()) {
            	String userName = apiKeyValidationDTO.getEndUserName();
            	PrivilegedCarbonContext.getThreadLocalCarbonContext().
            			setUsername(apiKeyValidationDTO.getEndUserName());
   	           	try {
					PrivilegedCarbonContext.getThreadLocalCarbonContext().
							setTenantId(IdentityUtil.getTenantIdOFUser(userName));
				} catch (IdentityException e) {
					log.error("Error while retrieving Tenant Id", e);
					return false;
				}
            	return true;
            } else {
            	throw new APIFaultException(apiKeyValidationDTO.getValidationStatus(), 
            			 "Access failure for API: " + context + ", version: " + version +
                         " with key: " + accessToken);
            }
    }
	
	private boolean doThrottle(Request request, String accessToken) throws APIFaultException {
				
		String apiName = request.getContextPath();
		String apiVersion = getAPIVersion(request);
		String apiIdentifier = apiName + "-" + apiVersion;
		
		APIThrottleHandler throttleHandler = null;
		ConfigurationContext cc = DataHolder.getServerConfigContext();
		
		if (cc.getProperty(apiIdentifier) == null) {
			throttleHandler = new APIThrottleHandler();
			/* Add the Throttle handler to ConfigContext against API Identifier */
			cc.setProperty(apiIdentifier, throttleHandler);
		} else {
			throttleHandler = (APIThrottleHandler) cc.getProperty(apiIdentifier);
		}
		
		if (throttleHandler.doThrottle(request, apiKeyValidationDTO, accessToken)) {
			return true;
		} else {
			throw new APIFaultException(APIManagerErrorConstants.API_THROTTLE_OUT, 
       			 "You have exceeded your quota");
		}
	}

    private boolean publishRequestStatistics(HttpServletRequest request, long currentTime) {

        if (publisher == null) {
            synchronized (this){
                if (publisher == null) {
                    try {
                        log.debug("Instantiating Data Publisher");
                        publisher = (APIMgtUsageDataPublisher)Class.forName(statsPublisherClass).newInstance();
                        publisher.init();
                    } catch (ClassNotFoundException e) {
                        log.error("Class not found " + statsPublisherClass);
                    } catch (InstantiationException e) {
                        log.error("Error instantiating " + statsPublisherClass);
                    } catch (IllegalAccessException e) {
                        log.error("Illegal access to " + statsPublisherClass);
                    }
                }
            }
        }

        APIStatsPublisher statsPublisher = new APIStatsPublisher(publisher, hostName);
        statsPublisher.publishRequestStatistics(apiKeyValidationDTO, request.getRequestURI(), request.getContextPath(),
                request.getPathInfo(), request.getMethod(), currentTime);

        return true;
    }

    private boolean publishResponseStatistics(HttpServletRequest request, long requestTime) {

        if (publisher == null) {
            synchronized (this){
                if (publisher == null) {
                    try {
                        log.debug("Instantiating Data Publisher");
                        publisher = (APIMgtUsageDataPublisher)Class.forName(statsPublisherClass).newInstance();
                        publisher.init();
                    } catch (ClassNotFoundException e) {
                        log.error("Class not found " + statsPublisherClass);
                    } catch (InstantiationException e) {
                        log.error("Error instantiating " + statsPublisherClass);
                    } catch (IllegalAccessException e) {
                        log.error("Illegal access to " + statsPublisherClass);
                    }
                }
            }
        }

        APIStatsPublisher statsPublisher = new APIStatsPublisher(publisher, hostName);
        statsPublisher.publishResponseStatistics(apiKeyValidationDTO, request.getRequestURI(), request.getContextPath(),
                request.getPathInfo(), request.getMethod(), requestTime);

        return true;
    }

    private String getAPIVersion(HttpServletRequest request) {
        int contextStartsIndex = (request.getRequestURI()).indexOf(request.getContextPath()) + 1;
        int length = request.getContextPath().length();
        String afterContext = (request.getRequestURI()).substring(contextStartsIndex + length);
        int SlashIndex = afterContext.indexOf(("/"));
        
        if (SlashIndex != -1) {
        	return afterContext.substring(0, SlashIndex);
        } else {
        	return afterContext;
        }
    }
  
    
    /**
     * Send an Error Response in application/xml content type
     * @param response
     * @param payload
     */
    private void handleFailure(Response response, String payload) {
    	response.setStatus(403);
		response.setContentType("application/xml");
		response.setCharacterEncoding("UTF-8");
		try {
			response.getWriter().write(payload);
		} catch (IOException e) {
			log.error("Error in sending fault response", e);
		}
    }
    
    /**
     * Generate the Error Payload
     * @param e APIFaultException
     * @param FaultNS
     * @param FaultNSPrefix
     * @return
     */
    private OMElement getFaultPayload(APIFaultException e, String FaultNS, String FaultNSPrefix) {
        OMFactory fac = OMAbstractFactory.getOMFactory();
        OMNamespace ns = fac.createOMNamespace(FaultNS,
        		FaultNSPrefix);
        OMElement payload = fac.createOMElement("fault", ns);

        OMElement errorCode = fac.createOMElement("code", ns);
        errorCode.setText(String.valueOf(e.getErrorCode()));
        OMElement errorMessage = fac.createOMElement("message", ns);
        errorMessage.setText(APIManagerErrorConstants.getFailureMessage(e.getErrorCode()));
        OMElement errorDetail = fac.createOMElement("description", ns);
        errorDetail.setText(e.getMessage());

        payload.addChild(errorCode);
        payload.addChild(errorMessage);
        payload.addChild(errorDetail);
        return payload;
    }
	
}

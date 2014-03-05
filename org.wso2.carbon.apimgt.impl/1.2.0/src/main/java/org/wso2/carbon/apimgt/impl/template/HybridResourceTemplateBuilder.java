/*
 *  Copyright WSO2 Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package org.wso2.carbon.apimgt.impl.template;

import org.apache.commons.lang.StringEscapeUtils;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;

import java.util.Map;

/**
 * Constructs the Production and Sandbox API Resource from the Template.
 */
public class HybridResourceTemplateBuilder implements ResourceTemplateBuilder{

    TemplateLoader templateLoader = TemplateLoader.getInstance();

    public String getResourceString(Map<String, String> resourceAttributeMap,
                                    Map<String, String> apiAttributeMap,
                                    int resourceNumber) throws APITemplateException{

        boolean productionEPSpecified =
                resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI) != null;
        boolean sandboxEPSpecified =
                resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_SANDBOX_URI) != null;
        String resourceTemplate = null;

        //If both production and sandbox endpoints have been specified
        if (productionEPSpecified && sandboxEPSpecified) {
            //Use the complex resource templates which have a sandbox endpoint as well.
            if(ApiMgtDAO.jwtGenerator != null){
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_COMPLEX_RESOURCE_WITH_JWT);
            }else{
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_COMPLEX_RESOURCE);
            }
            String endpoint = StringEscapeUtils.escapeXml(StringEscapeUtils.unescapeXml(
                    resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI)));
            String testEndpoint = StringEscapeUtils.escapeXml(StringEscapeUtils.unescapeXml(
                    resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_SANDBOX_URI)));
            String replacedStr = resourceTemplate.
                    replaceAll("\\[1\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI_TEMPLATE)).
                    replaceAll("\\[2\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_METHODS)).
                    replaceAll("\\[3\\]", endpoint).
                    replaceAll("\\[4\\]", apiAttributeMap.get(APITemplateBuilder.KEY_FOR_API_NAME)).
                    replaceAll("\\[5\\]", String.valueOf(resourceNumber)).
                    replaceAll("\\[6\\]", testEndpoint);

            return replacedStr;
        }
        else if(productionEPSpecified){
            //Use the normal resource templates which do not have a sandbox endpoint specified.
            if(ApiMgtDAO.jwtGenerator != null){
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_RESOURCE_WITH_JWT);
            }else{
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_RESOURCE);
            }

            String endpoint = StringEscapeUtils.escapeXml(StringEscapeUtils.unescapeXml(
                    resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI)));
            String replacedStr = resourceTemplate.
                    replaceAll("\\[1\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI_TEMPLATE)).
                    replaceAll("\\[2\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_METHODS)).
                    replaceAll("\\[3\\]", endpoint).
                    replaceAll("\\[4\\]", apiAttributeMap.get(APITemplateBuilder.KEY_FOR_API_NAME)).
                    replaceAll("\\[5\\]", String.valueOf(resourceNumber));

            return replacedStr;
        }
        else{
            if(ApiMgtDAO.jwtGenerator != null){
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_SANDBOX_RESOURCE_WITH_JWT);
            }else{
                resourceTemplate = templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_SANDBOX_RESOURCE);
            }

            String sandboxEndpoint = StringEscapeUtils.escapeXml(StringEscapeUtils.unescapeXml(
                    resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_SANDBOX_URI)));
            String replacedStr = resourceTemplate.
                    replaceAll("\\[1\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_URI_TEMPLATE)).
                    replaceAll("\\[2\\]", resourceAttributeMap.get(APITemplateBuilder.KEY_FOR_RESOURCE_METHODS)).
                    replaceAll("\\[3\\]", sandboxEndpoint).
                    replaceAll("\\[4\\]", apiAttributeMap.get(APITemplateBuilder.KEY_FOR_API_NAME)).
                    replaceAll("\\[5\\]", String.valueOf(resourceNumber));

            return replacedStr;
        }
    }
}

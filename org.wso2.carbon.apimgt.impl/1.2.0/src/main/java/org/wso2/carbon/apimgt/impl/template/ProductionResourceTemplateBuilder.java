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
 * Constructs the Production API Resource from the template.
 */
public class ProductionResourceTemplateBuilder implements ResourceTemplateBuilder{

    TemplateLoader templateLoader = TemplateLoader.getInstance();

    public String getResourceString(Map<String, String> resourceAttributeMap,
                                    Map<String, String> apiAttributeMap,
                                    int resourceNumber) throws APITemplateException{

        String resourceTemplate = null;

        if(ApiMgtDAO.jwtGenerator != null){
            resourceTemplate =
                    templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_PRODUCTION_ONLY_RESOURCE_WITH_JWT);
        }else{
            resourceTemplate =
                    templateLoader.getTemplate(TemplateLoader.TEMPLATE_TYPE_PRODUCTION_ONLY_RESOURCE);
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
}

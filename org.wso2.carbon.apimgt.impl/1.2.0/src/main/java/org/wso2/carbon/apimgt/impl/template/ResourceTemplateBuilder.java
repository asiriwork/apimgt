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

import java.util.Map;

/**
 * The Resource Template Builder interface which will be  used to define methods to be implemented in various Resource
 * Template Builder classes of different gateway environment types.
 */
public interface ResourceTemplateBuilder {

    /**
     * Constructs the Resource synapse xml from the template.
     * @param resourceAttributeMap - Attributes of the resource
     * @param apiAttributeMap - Attributes of the API
     * @param resourceNumber - The resource number on the API.
     * @return - The synapse xml of the resource
     * @throws APITemplateException - Thrown if an error occurs while constructing the xml from the template.
     */
    public String getResourceString(Map<String, String> resourceAttributeMap,
                                    Map<String, String> apiAttributeMap,
                                    int resourceNumber) throws APITemplateException;
}

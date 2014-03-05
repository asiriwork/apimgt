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

import org.wso2.carbon.apimgt.impl.APIConstants;

public class ResourceTemplateFactory {

    private static ResourceTemplateFactory instance = new ResourceTemplateFactory();

    private ResourceTemplateFactory() {
    }

    public static ResourceTemplateFactory getInstance() {
        return instance;
    }

    /**
     * Provides an instance of a ResourceTemplateBuilder matching the gateway environment type.
     * <p>
     * The Gateway environment type could be either 'production', 'sandbox' or 'hybrid'. The ResourceTemplateBuilder
     * is created according to the given type. Following are the ResourceTemplateBuilder instances that can be returned.
     * <ul>
     *     <li>production - ProductionResourceTemplateBuilder</li>
     *     <li>sandbox - SandboxResourceTemplateBuilder</li>
     *     <li>hybrid or anything else - HybridResourceTemplateBuilder</li>
     * </ul>
     * </p>
     * @param gatewayEnvironmentType - The type of gateway environment.
     * @return An instance of the ResourceTemplateBuilder.
     */
    public ResourceTemplateBuilder getResourceTemplateBuilder(String gatewayEnvironmentType){

        if(APIConstants.GATEWAY_ENV_TYPE_PRODUCTION.equals(gatewayEnvironmentType)){
            return new ProductionResourceTemplateBuilder();
        }
        else if(APIConstants.GATEWAY_ENV_TYPE_SANDBOX.equals(gatewayEnvironmentType)){
            return new SandboxResourceTemplateBuilder();
        }
        else{
            return new HybridResourceTemplateBuilder();
        }
    }
}

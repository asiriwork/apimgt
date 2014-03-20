package org.wso2.carbon.apimgt.usage.client.dto;

import java.util.*;

/**
 * Created by asiri on 3/18/14.
 */



public class AppUsageDTO {



    private String appName;

    private Map<String, APIUsageDTO> apiUsageDTOMap ;

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Map<String, APIUsageDTO>  getAPIUsageDTOMap() {return apiUsageDTOMap; }

    public void setAPIUsageDTOMap(Map<String, APIUsageDTO> apiUsageDTOMap) { this.apiUsageDTOMap = apiUsageDTOMap; }
}

package org.platformlambda.automation.models;

import java.util.List;

public class RouteInfo {

    public String url, service, authService, corsId, transformId;
    public List<String> methods;
    public int timeoutSeconds = 30;
    public String upload = "file";

}

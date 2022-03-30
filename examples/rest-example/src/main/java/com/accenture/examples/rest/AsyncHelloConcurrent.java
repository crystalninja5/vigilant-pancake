/*

    Copyright 2018-2022 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package com.accenture.examples.rest;

import io.vertx.core.Future;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.*;

/**
 * This demonstrates non-blocking fork-n-join using future results
 */
@Path("/hello")
public class AsyncHelloConcurrent {

    @GET
    @Path("/concurrent")
    @Produces({MediaType.TEXT_PLAIN, MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON, MediaType.TEXT_HTML})
    public void hello(@Context HttpServletRequest request,
                                     @Suspended AsyncResponse response) throws IOException, AppException {

        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();

        Map<String, Object> forward = new HashMap<>();
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements()) {
            String key = headers.nextElement();
            forward.put(key, request.getHeader(key));
        }
        int TOTAL = 10;
        List<EventEnvelope> parallelEvents = new ArrayList<>();
        for (int i=0; i < TOTAL; i++) {
            EventEnvelope event = new EventEnvelope();
            event.setTo("hello.world");
            event.setBody(forward);
            event.setHeader("request", "#"+(i+1));
            parallelEvents.add(event);
        }
        Future<List<EventEnvelope>> res = po.asyncRequest(parallelEvents, 3000);
        res.onSuccess(events -> {
            Map<String, Object> results = new HashMap<>();
            int n = 0;
            for (EventEnvelope evt: events) {
                n++;
                Map<String, Object> singleResult = new HashMap<>();
                singleResult.put("status", evt.getStatus());
                singleResult.put("headers", evt.getHeaders());
                singleResult.put("body", evt.getBody());
                singleResult.put("seq", evt.getCorrelationId());
                singleResult.put("execution_time", evt.getExecutionTime());
                singleResult.put("round_trip", evt.getRoundTrip());
                results.put("result_"+util.zeroFill(n, 999), singleResult);
            }
            response.resume(results);
        });
        res.onFailure(ex -> response.resume(new AppException(408, ex.getMessage())));
    }
}

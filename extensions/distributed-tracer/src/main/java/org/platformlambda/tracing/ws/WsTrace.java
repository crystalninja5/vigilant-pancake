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

package org.platformlambda.tracing.ws;

import org.platformlambda.core.annotations.WebSocketService;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.models.WsEnvelope;
import org.platformlambda.core.system.PostOffice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@WebSocketService("trace")
public class WsTrace implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(WsTrace.class);

    // route and txPath mapping
    private static final ConcurrentMap<String, String> connections = new ConcurrentHashMap<>();
    private static Set<String> currentPaths;

    public static synchronized Set<String> getConnections() {
        // scan connections only when there are changes
        if (currentPaths == null) {
            Set<String> result = new HashSet<>();
            for (String key: connections.keySet()) {
                result.add(connections.get(key));
            }
            currentPaths = result;
        }
        return currentPaths;
    }

    @Override
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws IOException {

        PostOffice po = PostOffice.getInstance();
        String route, token, txPath;

        if (headers.containsKey(WsEnvelope.TYPE)) {
            switch (headers.get(WsEnvelope.TYPE)) {
                case WsEnvelope.OPEN:
                    // the open event contains route, txPath, ip, path, query and token
                    route = headers.get(WsEnvelope.ROUTE);
                    txPath = headers.get(WsEnvelope.TX_PATH);
                    // TODO: implement authentication logic to validate the token
                    token = headers.get(WsEnvelope.TOKEN);
                    String ip = headers.get(WsEnvelope.IP);
                    String path = headers.get(WsEnvelope.PATH);
                    String query = headers.get(WsEnvelope.QUERY);
                    connections.put(route, txPath);
                    currentPaths = null;
                    log.info("Started {}, {}, ip={}, path={}, query={}, token={}", route, txPath, ip, path, query, token);
                    break;
                case WsEnvelope.CLOSE:
                    // the close event contains route and token for this websocket
                    route = headers.get(WsEnvelope.ROUTE);
                    token = headers.get(WsEnvelope.TOKEN);
                    connections.remove(route);
                    currentPaths = null;
                    log.info("Stopped {}, token={}", route, token);
                    break;
                case WsEnvelope.BYTES:
                    // the data event for byteArray payload contains route and txPath
                    route = headers.get(WsEnvelope.ROUTE);
                    txPath = headers.get(WsEnvelope.TX_PATH);
                    byte[] payload = (byte[]) body;
                    // just tell the browser that I have received the bytes
                    po.send(txPath, "received " + payload.length + " bytes");
                    log.info("{} got {} bytes", route, payload.length);
                    break;
                case WsEnvelope.STRING:
                    // the data event for string payload contains route and txPath
                    route = headers.get(WsEnvelope.ROUTE);
                    txPath = headers.get(WsEnvelope.TX_PATH);
                    String message = (String) body;
                    // just echo the message
                    po.send(txPath, message);
                    log.debug("{} received: {}", route, message);
                    break;
                default:
                    // this should not happen
                    log.error("Invalid event {} {}", headers, body);
                    break;
            }
        }
        // nothing to return because this is asynchronous
        return null;
    }

}

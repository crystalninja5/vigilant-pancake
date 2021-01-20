/*

    Copyright 2018-2021 Accenture Technology

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

package org.platformlambda.hazelcast;

import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.system.*;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.CryptoApi;
import org.platformlambda.core.util.ManagedCache;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

public class ServiceRegistry implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    private static final CryptoApi crypto = new CryptoApi();
    private static final String NOTIFICATION_INTERNAL = "notification.manager.internal";
    private static final String MANAGER = HazelcastSetup.MANAGER;
    private static final String PERSONALITY = EventNodeConnector.PERSONALITY;
    private static final String TYPE = ServiceDiscovery.TYPE;
    private static final String ROUTE = ServiceDiscovery.ROUTE;
    private static final String ORIGIN = ServiceDiscovery.ORIGIN;
    private static final String UNREGISTER = ServiceDiscovery.UNREGISTER;
    private static final String ADD = ServiceDiscovery.ADD;
    private static final String VERSION = "version";
    private static final String TARGET = "target";
    private static final String JOIN = "join";
    private static final String LEAVE = "leave";
    private static final String PING = "ping";
    private static final String PEERS = "peers";
    private static final String CHECKSUM = "checksum";
    private static final long APP_EXPIRY = 60 * 1000;
    /*
     * routes: route_name -> (origin, personality)
     * origins: origin -> last seen
     */
    private static final ManagedCache origins = ManagedCache.createCache("peer.origins", 10000);
    private static final ConcurrentMap<String, ConcurrentMap<String, String>> routes = new ConcurrentHashMap<>();
    private static List<String> peers = new ArrayList<>();
    private final boolean isServiceMonitor;

    public ServiceRegistry() {
        AppConfigReader reader = AppConfigReader.getInstance();
        this.isServiceMonitor = "true".equals(reader.getProperty("service.monitor", "false"));
    }

    public static Map<String, Map<String, String>> getAllRoutes() {
        return new HashMap<>(routes);
    }

    public static List<String> getInstances(String route) {
        if (routes.containsKey(route)) {
            return new ArrayList<>(routes.get(route).keySet());
        } else {
            return Collections.emptyList();
        }
    }

    public static Set<String> getAllOrigins() {
        Set<String> result = new HashSet<>(peers);
        result.addAll(origins.getMap().keySet());
        return result;
    }

    public static ConcurrentMap<String, String> getDestinations(String route) {
        return routes.get(route);
    }

    public static boolean destinationExists(String origin) {
        return origin.equals(Platform.getInstance().getOrigin()) || origins.exists(origin) || peers.contains(origin);
    }

    private String getChecksum() {
        StringBuilder sb = new StringBuilder();
        List<String> keys = new ArrayList<>(routes.keySet());
        if (keys.size() > 1) {
            Collections.sort(keys);
        }
        sb.append("*");
        for (String r: keys) {
            sb.append(r);
            sb.append(':');
            Map<String, String> instances = routes.get(r);
            List<String> innerKeys = new ArrayList<>(instances.keySet());
            if (innerKeys.size() > 1) {
                Collections.sort(innerKeys);
            }
            for (String i: innerKeys) {
                String v = instances.get(i);
                sb.append(i);
                sb.append('-');
                sb.append(v);
                sb.append('\n');
            }
        }
        Utility util = Utility.getInstance();
        return util.bytesToUrlBase64(crypto.getSHA1(util.getUTF(sb.toString())));
    }

    private List<String> getAdditions(List<String> updated) {
        List<String> additions = new ArrayList<>();
        for (String member: updated) {
            if (!peers.contains(member)) {
                additions.add(member);
            }
        }
        return additions;
    }

    private List<String> getRemoval(List<String> updated) {
        List<String> removal = new ArrayList<>();
        for (String member: peers) {
            if (!updated.contains(member)) {
                removal.add(member);
            }
        }
        return removal;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws Exception {
        if (isServiceMonitor) {
            // ignore requests since service monitor does not maintain routing table
            return false;
        }
        String type = headers.get(TYPE);
        // peer events from presence monitor
        if (PEERS.equals(type) && body instanceof List) {
            PostOffice po = PostOffice.getInstance();
            String me = Platform.getInstance().getOrigin();
            List<String> updated = (List<String>) body;
            List<String> additions = getAdditions(updated);
            List<String> removal = getRemoval(updated);
            peers = updated;
            for (String p: peers) {
                origins.put(p, Utility.getInstance().date2str(new Date(), true));
            }
            List<String> members = new ArrayList<>(peers);
            members.remove(me);
            if (members.isEmpty()) {
                log.info("No peers are detected. I am running alone.");
            } else {
                log.info("Found {} peer{}", members.size(), members.size() == 1? "" : "s");
            }
            if (!additions.isEmpty()) {
                for (String member: additions) {
                    if (!member.equals(me)) {
                        po.send(ServiceDiscovery.SERVICE_REGISTRY, new Kv(TYPE, JOIN), new Kv(ORIGIN, member));
                    }
                }
            }
            if (!removal.isEmpty()) {
                for (String member: removal) {
                    if (!member.equals(me)) {
                        po.send(ServiceDiscovery.SERVICE_REGISTRY, new Kv(TYPE, LEAVE), new Kv(ORIGIN, member));
                    }
                }
            }
        }
        if (PING.equals(type)) {
            broadcastChecksum(getChecksum());
            return true;
        }
        if (CHECKSUM.equals(type) && headers.containsKey(CHECKSUM) && headers.containsKey(ORIGIN)) {
            String myOrigin = Platform.getInstance().getOrigin();
            String origin = headers.get(ORIGIN);
            String extChecksum = headers.get(CHECKSUM);
            String myChecksum = getChecksum();
            if (!origin.equals(myOrigin)) {
                if (extChecksum.equals(myChecksum)) {
                    log.debug("Routing table matches with {}, checksum={}", origin, extChecksum);
                } else {
                    log.warn("Routing table checksum not matched. Syncing with {}", origin);
                    sendMyRoutes(origin);
                    downloadFromPeer(origin);
                }
            }
        }
        return processEvent(headers, body);
    }

    @SuppressWarnings("unchecked")
    private Object processEvent(Map<String, String> headers, Object body) throws IOException, TimeoutException, AppException {
        Platform platform = Platform.getInstance();
        PostOffice po = PostOffice.getInstance();
        String type = headers.get(TYPE);
        // when a node joins
        if (JOIN.equals(type) && headers.containsKey(ORIGIN)) {
            String myOrigin = platform.getOrigin();
            String origin = headers.get(ORIGIN);
            origins.put(origin, Utility.getInstance().date2str(new Date(), true));
            if (origin.equals(myOrigin)) {
                if (headers.containsKey(VERSION)) {
                    log.info("Presence monitor v"+headers.get(VERSION)+" detected");
                }
                registerMyRoutes();
                broadcast(origin, null, null, JOIN);
            } else {
                // send routing table of this node to the newly joined node
                if (!peers.contains(origin)) {
                    peers.add(origin);
                    log.info("Peer {} joined", origin);
                }
                sendMyRoutes(origin);
            }
            if (platform.hasRoute(NOTIFICATION_INTERNAL)) {
                po.send(NOTIFICATION_INTERNAL, new Kv(TYPE, JOIN), new Kv(ORIGIN, headers.get(ORIGIN)));
            }
        }
        // when a node leaves
        if (LEAVE.equals(type) && headers.containsKey(ORIGIN)) {
            // remove corresponding entries from routing table
            String origin = headers.get(ORIGIN);
            if (origin.equals(platform.getOrigin())) {
                // this happens when the service-monitor is down
                Set<String> all = getAllOrigins();
                for (String o : all) {
                    if (!o.equals(origin)) {
                        log.info("{} disconnected", o);
                        removeRoutesFromOrigin(o);
                    }
                }
                origins.clear();
                peers.clear();
                peers.add(origin);
                log.warn("Peer connections cleared");
            } else {
                if (peers.contains(origin)) {
                    peers.remove(origin);
                    log.info("Peer {} left", origin);
                }
                removeRoutesFromOrigin(origin);
            }
            if (platform.hasRoute(NOTIFICATION_INTERNAL)) {
                po.send(NOTIFICATION_INTERNAL, new Kv(TYPE, LEAVE), new Kv(ORIGIN, headers.get(ORIGIN)));
            }
        }
        // add route
        if (ADD.equals(type) && headers.containsKey(ORIGIN)) {
            String origin = headers.get(ORIGIN);
            if (headers.containsKey(ROUTE) && headers.containsKey(PERSONALITY)) {
                // add a single route
                String route = headers.get(ROUTE);
                String personality = headers.get(PERSONALITY);
                if (origin.equals(platform.getOrigin())) {
                    broadcast(origin, route, personality, ADD);
                }
                // add to routing table
                addRoute(origin, route, personality);
            } else if (body instanceof Map) {
                // add a list of routes
                Map<String, String> routeMap = (Map<String, String>) body;
                int count = routeMap.size();
                log.debug("Loading {} route{} from {}", count, count == 1? "" : "s", origin);
                for (String route: routeMap.keySet()) {
                    String personality = routeMap.get(route);
                    addRoute(origin, route, personality);
                }
            }
        }
        // clear a route
        if (UNREGISTER.equals(type) && headers.containsKey(ROUTE) && headers.containsKey(ORIGIN)) {
            String route = headers.get(ROUTE);
            String origin = headers.get(ORIGIN);
            if (origin.equals(platform.getOrigin())) {
                broadcast(origin, route, null, UNREGISTER);
            }
            // remove from routing table
            removeRoute(origin, route);
        }
        return true;
    }

    private void sendMyRoutes(String origin) throws IOException {
        PostOffice po = PostOffice.getInstance();
        String myOrigin = Platform.getInstance().getOrigin();
        Map<String, String> routeMap = new HashMap<>();
        for (String r : routes.keySet()) {
            ConcurrentMap<String, String> originMap = routes.get(r);
            if (originMap.containsKey(myOrigin)) {
                routeMap.put(r, originMap.get(myOrigin));
            }
        }
        if (!routeMap.isEmpty()) {
            EventEnvelope request = new EventEnvelope();
            request.setTo(ServiceDiscovery.SERVICE_REGISTRY + "@" + origin)
                    .setHeader(TYPE, ADD).setHeader(ORIGIN, myOrigin).setBody(routeMap);
            po.send(request);
        }
    }

    private void broadcast(String origin, String route, String personality, String type) throws IOException, TimeoutException, AppException {
        PostOffice po = PostOffice.getInstance();
        Utility util = Utility.getInstance();
        if (origin.equals(Platform.getInstance().getOrigin())) {
            // broadcast to peers
            List<String> peers = getPeers();
            for (String p : peers) {
                if (!p.equals(Platform.getInstance().getOrigin())) {
                    EventEnvelope request = new EventEnvelope();
                    request.setTo(ServiceDiscovery.SERVICE_REGISTRY + "@" + p)
                            .setHeader(TYPE, type).setHeader(ORIGIN, origin);
                    if (route != null) {
                        request.setHeader(ROUTE, route);
                    }
                    if (personality != null) {
                        request.setHeader(PERSONALITY, personality);
                    }
                    origins.put(p, util.date2str(new Date(), true));
                    po.send(request);
                }
            }
        }
    }

    private void downloadFromPeer(String peer) throws IOException {
        String origin = Platform.getInstance().getOrigin();
        EventEnvelope request = new EventEnvelope();
        request.setTo(ServiceDiscovery.SERVICE_REGISTRY + "@" + peer)
                .setHeader(TYPE, JOIN).setHeader(ORIGIN, origin);
        origins.put(peer, Utility.getInstance().date2str(new Date(), true));
        PostOffice.getInstance().send(request);
    }

    private void broadcastChecksum(String checkSum) throws IOException {
        PostOffice po = PostOffice.getInstance();
        String origin = Platform.getInstance().getOrigin();
        // broadcast to peers
        if (!peers.isEmpty()) {
            List<String> myPeers = new ArrayList<>(peers);
            myPeers.remove(origin);
            if (myPeers.isEmpty()) {
                log.info("No need to sync with peers because I am running alone");
            } else {
                log.info("Sending checksum {} to {}", checkSum, myPeers);
                for (String p : myPeers) {
                    EventEnvelope request = new EventEnvelope();
                    request.setTo(ServiceDiscovery.SERVICE_REGISTRY + "@" + p).setHeader(ORIGIN, origin)
                            .setHeader(TARGET, p).setHeader(TYPE, CHECKSUM).setHeader(CHECKSUM, checkSum);
                    po.send(request);
                }
            }
        }
    }

    private void addRoute(String origin, String route, String personality) {
        if (!routes.containsKey(route)) {
            routes.put(route, new ConcurrentHashMap<>());
        }
        ConcurrentMap<String, String> originMap = routes.get(route);
        if (!originMap.containsKey(origin)) {
            originMap.put(origin, personality);
            origins.put(origin, Utility.getInstance().date2str(new Date(), true));
            log.info("{} {}.{} registered", route, personality, origin);
        }
    }

    private void removeRoute(String origin, String route) {
        boolean deleted = false;
        if (routes.containsKey(route)) {
            ConcurrentMap<String, String> originMap = routes.get(route);
            if (originMap.containsKey(origin)) {
                originMap.remove(origin);
                deleted = true;
            }
            if (originMap.isEmpty()) {
                routes.remove(route);
            }
        }
        if (deleted) {
            log.info("{} {} unregistered", route, origin);
        }
    }

    private void removeRoutesFromOrigin(String origin) {
        List<String> routeList = new ArrayList<>(routes.keySet());
        for (String r: routeList) {
            removeRoute(origin, r);
        }
        origins.remove(origin);
    }

    private void registerMyRoutes() {
        Platform platform = Platform.getInstance();
        String origin = platform.getOrigin();
        // copy local registry to global registry
        ConcurrentMap<String, ServiceDef> routingTable = platform.getLocalRoutingTable();
        String personality = ServerPersonality.getInstance().getType().toString();
        for (String r: routingTable.keySet()) {
            ServiceDef def = routingTable.get(r);
            if (!def.isPrivate()) {
                addRoute(origin, def.getRoute(), personality);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getPeers() throws IOException, TimeoutException, AppException {
        PostOffice po = PostOffice.getInstance();
        EventEnvelope response = po.request(MANAGER, 10000, new Kv(TYPE, TopicManager.LIST_TIMESTAMP));
        if (response.getBody() instanceof Map) {
            Map<String, String> peers = (Map<String, String>) response.getBody();
            List<String> result = new ArrayList<>();
            for (String p: peers.keySet()) {
                if (!isExpired(peers.get(p))) {
                    result.add(p);
                }
            }
            return result;

        } else {
            return Collections.EMPTY_LIST;
        }
    }

    private boolean isExpired(String isoTimestamp) {
        Date time = Utility.getInstance().str2date(isoTimestamp);
        return System.currentTimeMillis() - time.getTime() > APP_EXPIRY;
    }

}

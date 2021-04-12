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

package org.platformlambda.kafka.pubsub;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.models.PubSubProvider;
import org.platformlambda.core.serializers.MsgPack;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;
import org.platformlambda.core.websocket.common.MultipartPayload;
import org.platformlambda.core.websocket.common.WsConfigurator;
import org.platformlambda.kafka.KafkaSetup;
import org.platformlambda.kafka.services.TopicManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class KafkaPubSub implements PubSubProvider {
    private static final Logger log = LoggerFactory.getLogger(KafkaPubSub.class);

    private static final MsgPack msgPack = new MsgPack();
    private static final String MANAGER = KafkaSetup.MANAGER;
    private static final String TYPE = "type";
    private static final String PARTITIONS = "partitions";
    private static final String CREATE = "create";
    private static final String LIST = "list";
    private static final String EXISTS = "exists";
    private static final String DELETE = "delete";
    private static final String TOPIC = "topic";
    private static final int MAX_PAYLOAD = WsConfigurator.getInstance().getMaxBinaryPayload() - 256;
    private static final ConcurrentMap<String, EventConsumer> subscribers = new ConcurrentHashMap<>();
    private static long seq = 0, totalEvents = 0;

    private String producerId = null;
    private KafkaProducer<String, byte[]> producer = null;

    public KafkaPubSub() {
        try {
            // start Kafka Topic Manager
            Platform.getInstance().registerPrivate(MANAGER, new TopicManager(), 1);
        } catch (IOException e) {
            log.error("Unable to start producer - {}", e.getMessage());
        }
        // clean up subscribers when application stops
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    private Properties getProperties() {
        Properties properties = new Properties();
        properties.putAll(KafkaSetup.getKafkaProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "1"); // Setting to "1" ensures that the message is received by the leader
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArraySerializer.class);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 15000);
        return properties;
    }

    private void sendEvent(String topic, int partition, String id,
                           List<Header> headers, byte[] payload, boolean isEvent) throws IOException {
        startProducer();
        try {
            // Segmentation for large payload supported for embedded event only
            if (isEvent && payload.length > MAX_PAYLOAD) {
                int total = (payload.length / MAX_PAYLOAD) + (payload.length % MAX_PAYLOAD == 0 ? 0 : 1);
                ByteArrayInputStream in = new ByteArrayInputStream(payload);
                for (int i = 0; i < total; i++) {
                    // To distinguish from a normal payload, the segmented block MUST not have a "TO" value.
                    EventEnvelope block = new EventEnvelope();
                    block.setHeader(MultipartPayload.ID, id);
                    block.setHeader(MultipartPayload.COUNT, String.valueOf(i + 1));
                    block.setHeader(MultipartPayload.TOTAL, String.valueOf(total));
                    byte[] segment = new byte[MAX_PAYLOAD];
                    int size = in.read(segment);
                    block.setBody(size == MAX_PAYLOAD ? segment : Arrays.copyOfRange(segment, 0, size));
                    if (partition < 0) {
                        producer.send(new ProducerRecord<>(topic, id + i, block.toBytes()))
                                .get(10000, TimeUnit.MILLISECONDS);
                    } else {
                        producer.send(new ProducerRecord<>(topic, partition,id + i, block.toBytes(), headers))
                                .get(10000, TimeUnit.MILLISECONDS);
                    }
                    totalEvents++;
                    log.info("Sending block {} of {} to {}", i + 1, total, topic);
                }
            } else {
                if (partition < 0) {
                    producer.send(new ProducerRecord<>(topic, id, payload))
                            .get(10000, TimeUnit.MILLISECONDS);
                } else {
                    producer.send(new ProducerRecord<>(topic, partition, id, payload, headers))
                            .get(10000, TimeUnit.MILLISECONDS);
                }
                totalEvents++;
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Unable to publish event to {} - {}", topic, e.getMessage());
            closeProducer();
        }
    }

    private void validateTopicName(String route) throws IOException {
        // guarantee that only valid service name is registered
        Utility util = Utility.getInstance();
        if (!util.validServiceName(route)) {
            throw new IOException("Invalid route name - use 0-9, a-z, period, hyphen or underscore characters");
        }
        String path = util.filteredServiceName(route);
        if (path.length() == 0) {
            throw new IOException("Invalid route name");
        }
        if (!path.contains(".")) {
            throw new IOException("Invalid route "+route+" because it is missing dot separator(s). e.g. hello.world");
        }
        if (util.reservedExtension(path)) {
            throw new IOException("Invalid route "+route+" because it cannot use a reserved extension");
        }
        if (util.reservedFilename(path)) {
            throw new IOException("Invalid route "+route+" which is a reserved Windows filename");
        }
    }

    @Override
    public boolean createTopic(String topic) throws IOException {
        return createTopic(topic, 1);
    }

    @Override
    public boolean createTopic(String topic, int partitions) throws IOException {
        validateTopicName(topic);
        try {
            EventEnvelope init = PostOffice.getInstance().request(MANAGER, 20000,
                                    new Kv(TYPE, CREATE), new Kv(TOPIC, topic), new Kv(PARTITIONS, partitions));
            if (init.getBody() instanceof Boolean) {
                return(Boolean) init.getBody();
            } else {
                return false;
            }
        } catch (TimeoutException | AppException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public void deleteTopic(String topic) throws IOException {
        try {
            PostOffice.getInstance().request(MANAGER, 20000, new Kv(TYPE, DELETE), new Kv(TOPIC, topic));
        } catch (TimeoutException | AppException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public boolean exists(String topic) throws IOException {
        validateTopicName(topic);
        try {
            EventEnvelope response = PostOffice.getInstance().request(MANAGER, 20000,
                                        new Kv(TYPE, EXISTS), new Kv(TOPIC, topic));
            if (response.getBody() instanceof Boolean) {
                return (Boolean) response.getBody();
            } else {
                return false;
            }
        } catch (TimeoutException | AppException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public int partitionCount(String topic) throws IOException {
        validateTopicName(topic);
        try {
            EventEnvelope response = PostOffice.getInstance().request(MANAGER, 20000,
                                        new Kv(TYPE, PARTITIONS), new Kv(TOPIC, topic));
            if (response.getBody() instanceof Integer) {
                return (Integer) response.getBody();
            } else {
                return -1;
            }
        } catch (TimeoutException | AppException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> list() throws IOException {
        try {
            EventEnvelope init = PostOffice.getInstance().request(MANAGER, 20000, new Kv(TYPE, LIST));
            if (init.getBody() instanceof List) {
                return (List<String>) init.getBody();
            } else {
                return Collections.EMPTY_LIST;
            }
        } catch (TimeoutException | AppException e) {
            throw new IOException(e.getMessage());
        }
    }

    private synchronized void startProducer() {
        if (producer == null) {
            // create unique ID from origin ID by dropping date prefix and adding a sequence suffix
            String id = (Platform.getInstance().getOrigin()+"ps"+(++seq)).substring(8);
            Properties properties = getProperties();
            properties.put(ProducerConfig.CLIENT_ID_CONFIG, id);
            producer = new KafkaProducer<>(properties);
            producerId = properties.getProperty(ProducerConfig.CLIENT_ID_CONFIG);
            log.info("Producer {} ready", properties.getProperty(ProducerConfig.CLIENT_ID_CONFIG));
        }
    }

    private synchronized void closeProducer() {
        if (producer != null) {
            try {
                producer.close();
                log.info("Producer {} released, delivered: {}", producerId, totalEvents);
            } catch (Exception e) {
                // ok to ignore
            }
            producer = null;
            producerId = null;
            totalEvents = 0;
        }
    }

    @Override
    public void publish(String topic, Map<String, String> headers, Object body) throws IOException {
        publish(topic, -1, headers, body);
    }

    @Override
    public void publish(String topic, int partition, Map<String, String> headers, Object body) throws IOException {
        validateTopicName(topic);
        Utility util = Utility.getInstance();
        Map<String, String> eventHeaders = headers == null? new HashMap<>() : headers;
        EventEnvelope event = new EventEnvelope();
        List<Header> headerList = new ArrayList<>();
        if (eventHeaders.containsKey(EventProducer.EMBED_EVENT) && body instanceof byte[]) {
            headerList.add(new RecordHeader(EventProducer.EMBED_EVENT, util.getUTF("1")));
            String recipient = eventHeaders.get(EventProducer.RECIPIENT);
            if (recipient != null) {
                headerList.add(new RecordHeader(EventProducer.RECIPIENT, util.getUTF(recipient)));
            }
            sendEvent(topic, partition, event.getId(), headerList, (byte[]) body, true);
        } else {
            for (String h: eventHeaders.keySet()) {
                headerList.add(new RecordHeader(h, util.getUTF(eventHeaders.get(h))));
            }
            final byte[] payload;
            if (body instanceof byte[]) {
                payload = (byte[]) body;
                headerList.add(new RecordHeader(EventProducer.DATA_TYPE, util.getUTF(EventProducer.BYTES_DATA)));
            } else if (body instanceof String) {
                payload = util.getUTF((String) body);
                headerList.add(new RecordHeader(EventProducer.DATA_TYPE, util.getUTF(EventProducer.TEXT_DATA)));
            } else if (body instanceof Map) {
                payload = msgPack.pack(body);
                headerList.add(new RecordHeader(EventProducer.DATA_TYPE, util.getUTF(EventProducer.MAP_DATA)));
            } else if (body instanceof List) {
                payload = msgPack.pack(body);
                headerList.add(new RecordHeader(EventProducer.DATA_TYPE, util.getUTF(EventProducer.LIST_DATA)));
            } else {
                // other primitive and PoJo are serialized as JSON string
                payload = SimpleMapper.getInstance().getMapper().writeValueAsBytes(body);
                headerList.add(new RecordHeader(EventProducer.DATA_TYPE, util.getUTF(EventProducer.TEXT_DATA)));
            }
            sendEvent(topic, partition, event.getId(), headerList, payload, false);
        }
    }

    @Override
    public void subscribe(String topic, LambdaFunction listener, String... parameters) throws IOException {
        subscribe(topic, -1, listener, parameters);
    }

    @Override
    public void subscribe(String topic, int partition, LambdaFunction listener, String... parameters) throws IOException {
        validateTopicName(topic);
        String topicPartition = topic + (partition < 0? "" : "." + partition);
        if (parameters.length == 2 || parameters.length == 3) {
            if (parameters.length == 3 && !Utility.getInstance().isNumeric(parameters[2])) {
                throw new IOException("topic offset must be numeric");
            }
            if (subscribers.containsKey(topicPartition) || Platform.getInstance().hasRoute(topicPartition)) {
                throw new IOException(topicPartition+" is already subscribed");
            }
            EventConsumer consumer = new EventConsumer(getProperties(), topic, partition, parameters);
            consumer.start();
            Platform.getInstance().registerPrivate(topicPartition, listener, 1);
            subscribers.put(topicPartition, consumer);
        } else {
            throw new IOException("Check parameters: clientId, groupId and optional offset pointer");
        }
    }

    @Override
    public void unsubscribe(String topic) throws IOException {
        unsubscribe(topic, -1);
    }

    @Override
    public void unsubscribe(String topic, int partition) throws IOException {
        String topicPartition = topic + (partition < 0? "" : "." + partition);
        validateTopicName(topic);
        Platform platform = Platform.getInstance();
        if (platform.hasRoute(topicPartition) && subscribers.containsKey(topicPartition)) {
            EventConsumer consumer = subscribers.get(topicPartition);
            platform.release(topicPartition);
            subscribers.remove(topicPartition);
            consumer.shutdown();
        } else {
            if (partition > -1) {
                throw new IOException(topicPartition +
                        " has not been subscribed by this application instance");
            } else {
                throw new IOException(topic + " has not been subscribed by this application instance");
            }
        }
    }

    private void shutdown() {
        closeProducer();
        for (String topic: subscribers.keySet()) {
            EventConsumer consumer = subscribers.get(topic);
            consumer.shutdown();
        }
    }

}

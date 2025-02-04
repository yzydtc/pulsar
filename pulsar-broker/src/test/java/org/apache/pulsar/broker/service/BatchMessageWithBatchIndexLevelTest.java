/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import com.google.common.collect.Lists;
import lombok.SneakyThrows;
import org.apache.pulsar.broker.service.persistent.PersistentDispatcherMultipleConsumers;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.ConsumerImpl;
import org.apache.pulsar.common.util.FutureUtil;
import org.awaitility.Awaitility;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.testng.Assert.assertEquals;

@Test(groups = "broker")
public class BatchMessageWithBatchIndexLevelTest extends BatchMessageTest {

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        conf.setAcknowledgmentAtBatchIndexLevelEnabled(true);
        super.baseSetup();
    }

    @Test
    @SneakyThrows
    public void testBatchMessageAck() {
        int numMsgs = 40;
        final String topicName = "persistent://prop/ns-abc/batchMessageAck-" + UUID.randomUUID();
        final String subscriptionName = "sub-batch-1";

        ConsumerImpl<byte[]> consumer = (ConsumerImpl<byte[]>) pulsarClient
                .newConsumer()
                .topic(topicName)
                .subscriptionName(subscriptionName)
                .receiverQueueSize(10)
                .subscriptionType(SubscriptionType.Shared)
                .enableBatchIndexAcknowledgment(true)
                .negativeAckRedeliveryDelay(100, TimeUnit.MILLISECONDS)
                .subscribe();

        Producer<byte[]> producer = pulsarClient
                .newProducer()
                .topic(topicName)
                .batchingMaxMessages(20)
                .batchingMaxPublishDelay(1, TimeUnit.HOURS)
                .enableBatching(true)
                .create();

        List<CompletableFuture<MessageId>> sendFutureList = Lists.newArrayList();
        for (int i = 0; i < numMsgs; i++) {
            byte[] message = ("batch-message-" + i).getBytes();
            sendFutureList.add(producer.newMessage().value(message).sendAsync());
        }
        FutureUtil.waitForAll(sendFutureList).get();
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopicReference(topicName).get();
        PersistentDispatcherMultipleConsumers dispatcher = (PersistentDispatcherMultipleConsumers) topic
                .getSubscription(subscriptionName).getDispatcher();
        Message<byte[]> receive1 = consumer.receive();
        Message<byte[]> receive2 = consumer.receive();
        consumer.acknowledge(receive1);
        consumer.acknowledge(receive2);
        Awaitility.await().untilAsserted(() -> {
            assertEquals(dispatcher.getConsumers().get(0).getUnackedMessages(), 18);
        });
        Message<byte[]> receive3 = consumer.receive();
        Message<byte[]> receive4 = consumer.receive();
        consumer.acknowledge(receive3);
        consumer.acknowledge(receive4);
        Awaitility.await().untilAsserted(() -> {
            assertEquals(dispatcher.getConsumers().get(0).getUnackedMessages(), 16);
        });
        Message<byte[]> receive5 = consumer.receive();
        consumer.negativeAcknowledge(receive5);
        Awaitility.await().pollInterval(1, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertEquals(dispatcher.getConsumers().get(0).getUnackedMessages(), 0);
        });
        consumer.receive();
        Awaitility.await().untilAsserted(() -> {
            assertEquals(dispatcher.getConsumers().get(0).getUnackedMessages(), 16);
        });
    }
}

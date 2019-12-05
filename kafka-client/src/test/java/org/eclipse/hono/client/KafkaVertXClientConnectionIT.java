/**
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.client;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import lombok.extern.slf4j.Slf4j;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles({"test"})
@ContextConfiguration(classes = {TestConfiguration.class})
@Slf4j
public class KafkaVertXClientConnectionIT {
  @Autowired
  private TestConfigurationProperties properties;

  private static final String TOPIC = "test";
  private static final int TOPIC_PARTITIONS = 8;

  private final Vertx vertx = Vertx.vertx();


  private KafkaConsumer<String, String> consumer;
  private KafkaProducer<String, String> producer;


  @BeforeEach
  public void setup() {
    properties.getKafka().setMaxPoll(Duration.ofMinutes(15));

    consumer = KafkaConsumer.create(vertx,
        properties.getKafka().buildConsumerProperties(UUID.randomUUID().toString()));

    producer = KafkaProducer.create(vertx,
        properties.getKafka().buildProducerProperties(UUID.randomUUID().toString()));
  }

  @AfterEach
  public void clean() {
    consumer.close(res -> assertThat(res.succeeded()).isTrue());
    producer.close(res -> assertThat(res.succeeded()).isTrue());
  }


  @Test
  public void listTopics() throws Exception {
    final CountDownLatch listCall = new CountDownLatch(1);
    consumer.listTopics(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).hasEntrySatisfying(TOPIC,
          list -> assertThat(list).hasSize(TOPIC_PARTITIONS));

      listCall.countDown();
    });

    assertThat(listCall.await(30, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void partitionsFor() throws Exception {
    final CountDownLatch listCall = new CountDownLatch(1);

    consumer.partitionsFor(TOPIC, ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).hasSize(TOPIC_PARTITIONS);

      listCall.countDown();
    });

    assertThat(listCall.await(30, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void partitionsAssignedHandler() throws Exception {

    final CountDownLatch assignedCall = new CountDownLatch(1);
    consumer.partitionsAssignedHandler(topicPartitions -> {
      assertThat(topicPartitions).hasSize(TOPIC_PARTITIONS);
      assignedCall.countDown();
    });

    subscribeToTopic(TOPIC, consumer);
    consumer.poll(100, res -> assertThat(res.succeeded()).isTrue());
    assertThat(assignedCall.await(30, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void partitionsRevokedHandler() throws Exception {
    // Original assignment to (only) consumer 1
    final CountDownLatch assignedCall = new CountDownLatch(1);
    consumer.partitionsAssignedHandler(topicPartitions -> {
      log.info("Consumer 1 assigned {} partitions: {}", topicPartitions.size(), topicPartitions);
      assertThat(topicPartitions).hasSize(TOPIC_PARTITIONS);
      assignedCall.countDown();
    });

    subscribeToTopic(TOPIC, consumer);

    final long consumerPoll = vertx.setPeriodic(1000,
        timerId -> consumer.poll(100, res -> assertThat(res.succeeded()).isTrue()));
    assertThat(assignedCall.await(1, TimeUnit.MINUTES)).isTrue();

    // Full unassignment from (only) consumer 1
    final CountDownLatch unassignedCall = new CountDownLatch(1);
    consumer.partitionsRevokedHandler(topicPartitions -> {
      log.info("Consumer 1 revoked {} partitions: {}", topicPartitions.size(), topicPartitions);
      assertThat(topicPartitions).hasSize(TOPIC_PARTITIONS);
      unassignedCall.countDown();
    });

    // Half assignment to consumer 1
    final CountDownLatch reassignedCall = new CountDownLatch(1);
    consumer.partitionsAssignedHandler(topicPartitions -> {
      log.info("Consumer 1 assigned {} partitions: {}", topicPartitions.size(), topicPartitions);
      assertThat(topicPartitions).hasSize(TOPIC_PARTITIONS / 2);
      reassignedCall.countDown();
    });

    final KafkaConsumer<String, String> consumer2 = KafkaConsumer.create(vertx,
        properties.getKafka().buildConsumerProperties("-testConsumer2"));

    long consumer2Poll = -1;
    try {
      // Half assignment to consumer 2
      final CountDownLatch assigned2Call = new CountDownLatch(1);
      consumer2.partitionsAssignedHandler(topicPartitions -> {
        log.info("Consumer 2 assigned {} partitions: {}", topicPartitions.size(), topicPartitions);
        assertThat(topicPartitions).hasSize(TOPIC_PARTITIONS / 2);
        assigned2Call.countDown();
      });
      subscribeToTopic(TOPIC, consumer2);

      consumer2Poll = vertx.setPeriodic(1000,
          timerId -> consumer2.poll(100, res -> assertThat(res.succeeded()).isTrue()));


      assertThat(reassignedCall.await(1, TimeUnit.MINUTES)).isTrue();
      assertThat(assigned2Call.await(1, TimeUnit.MINUTES)).isTrue();
      assertThat(unassignedCall.await(1, TimeUnit.MINUTES)).isTrue();

    } finally {
      vertx.cancelTimer(consumerPoll);
      vertx.cancelTimer(consumer2Poll);
      consumer2.close(res -> assertThat(res.succeeded()).isTrue());
    }
  }

  @Test
  public void sendMessagesAndEnsureOffsetIsSetCorrectly() throws Exception {
    final Set<String> sendMessages = new ConcurrentHashSet<>();

    consumeMessages(sendMessages, consumer);

    subscribeToTopic(TOPIC, consumer);

    sendMessage(sendMessages, producer, 10);

    Awaitility.await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofMillis(10))
        .until(() -> sendMessages.isEmpty());

    unsubscribeFromTopic(consumer);

    sendMessage(sendMessages, producer, 10);

    subscribeToTopic(TOPIC, consumer);

    Awaitility.await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofMillis(10))
        .until(() -> sendMessages.isEmpty());
  }

  private static void consumeMessages(final Set<String> sendMessages,
      final KafkaConsumer<String, String> consumer) {
    consumer.handler(record -> {

      log.info("Consumed record with key=" + record.key() + ",value=" + record.value()
          + ",partition=" + record.partition() + ",offset=" + record.offset());
      consumer.commit();

      assertThat(sendMessages.remove(record.value())).isTrue();
    });
  }

  private static void sendMessage(final Set<String> sendMessages,
      final KafkaProducer<String, String> producer, final int messages)
      throws InterruptedException {
    assertThat(sendMessages).isEmpty();

    final CountDownLatch send = new CountDownLatch(messages);
    for (int i = 0; i < messages; i++) {
      final KafkaProducerRecord<String, String> record =
          KafkaProducerRecord.create(TOPIC, null, UUID.randomUUID().toString());

      producer.send(record, done -> {
        assertThat(done.succeeded()).isTrue();

        final RecordMetadata recordMetadata = done.result();
        log.debug("Message " + record.value() + " written on topic=" + recordMetadata.getTopic()
            + ", partition=" + recordMetadata.getPartition() + ", offset="
            + recordMetadata.getOffset());

        sendMessages.add(record.value());
        send.countDown();
      });
    }
    assertThat(send.await(10, TimeUnit.SECONDS)).isTrue();

    producer.flush(flushed -> {
      log.info("flushed");
    });

    assertThat(sendMessages).hasSize(messages);
  }

  private static void subscribeToTopic(final String topic,
      final KafkaConsumer<String, String> consumer) throws InterruptedException {
    final CountDownLatch subscribed = new CountDownLatch(1);
    consumer.subscribe(topic, ar -> {
      assertThat(ar.succeeded()).isTrue();
      subscribed.countDown();
    });
    assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
  }

  private static void unsubscribeFromTopic(final KafkaConsumer<String, String> consumer)
      throws InterruptedException {
    final CountDownLatch subscribed = new CountDownLatch(1);
    consumer.unsubscribe(ar -> {
      assertThat(ar.succeeded()).isTrue();
      subscribed.countDown();
    });
    assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
  }



}

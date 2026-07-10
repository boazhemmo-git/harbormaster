package io.harbormaster.ingest;

import io.harbormaster.config.SourceProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link KafkaSource} against a real in-JVM KRaft broker (no Docker,
 * no ZooKeeper) — proving the consumer group wiring, the String serde round
 * trip, and the simulation→Kafka producer bridge all work end to end.
 */
class KafkaSourceIntegrationTest {

    private static final String TOPIC = "ais.nmea";
    private static EmbeddedKafkaBroker broker;
    private static String bootstrap;

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();
        bootstrap = broker.getBrokersAsString();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    @Test
    void consumesRawLinesFromTheTopicIntact() {
        var received = new ConcurrentLinkedQueue<String>();
        var source = new KafkaSource(
                new SourceProperties.Kafka(bootstrap, TOPIC, "test-consume", false),
                simConfig());

        // Distinct, realistic AIVDM sentences. The source forwards them raw —
        // decoding is a downstream concern, so exact-value fidelity is what matters.
        List<String> sent = List.of(
                "!AIVDM,1,1,,A,15M67FC000G?ufbE`FepT@3n00Sa,0*5C",
                "!AIVDM,1,1,,B,13aEOK?P00PD2wVMdLDRhgvL289?,0*26",
                "!AIVDM,1,1,,A,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C");

        source.start(line -> received.add(line.raw()));
        try (Producer<String, String> producer = new KafkaProducer<>(producerProps())) {
            // "latest" consumers only see traffic after they join, so publish in a
            // loop until every distinct sentence has been observed.
            awaitTrue(Duration.ofSeconds(20), () -> {
                sent.forEach(s -> producer.send(new ProducerRecord<>(TOPIC, s)));
                producer.flush();
                sleep(100);
                return received.containsAll(sent);
            });
        } finally {
            source.stop();
        }

        assertThat(Set.copyOf(received)).containsAll(sent);
    }

    @Test
    void producerBridgePublishesTheScriptedSimulation() {
        var received = new ConcurrentLinkedQueue<String>();
        var source = new KafkaSource(
                new SourceProperties.Kafka(bootstrap, TOPIC, "test-bridge", true),
                simConfig());

        source.start(line -> received.add(line.raw()));
        try {
            // The bridge runs the scripted generator as a producer; the consumer
            // should start seeing genuine AIVDM sentences within a few ticks.
            awaitTrue(Duration.ofSeconds(20),
                    () -> received.stream().anyMatch(s -> s.startsWith("!AIVDM")));
        } finally {
            source.stop();
        }

        assertThat(received).anyMatch(s -> s.startsWith("!AIVDM"));
    }

    private static SourceProperties.Simulation simConfig() {
        // Small, fast-reporting fleet keeps the bridge test brisk.
        return new SourceProperties.Simulation(52.2, 3.8, 20, 20260709);
    }

    private static Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

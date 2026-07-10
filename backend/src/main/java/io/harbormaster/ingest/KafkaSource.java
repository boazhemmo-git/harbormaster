package io.harbormaster.ingest;

import io.harbormaster.config.SourceProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Consumes raw NMEA lines from a Kafka topic. This is the shape of a real
 * shore deployment: many edge receivers publish sentences to a topic, and the
 * pipeline scales out horizontally as a consumer group — Kafka rebalances
 * partitions across however many instances are running.
 *
 * <p>Uses the plain {@code kafka-clients} consumer in a virtual thread rather
 * than a Spring Kafka listener container, to stay consistent with the other
 * sources (each owns its thread and lifecycle) and to keep Spring off the
 * ingest hot path. Offsets are committed only after a batch has been handed to
 * the sink — at-least-once, which for an idempotent position store is the
 * right trade (a redelivered fix overwrites to the same value).
 *
 * <p>Records are published round-robin (null key). A production feed would key
 * by MMSI so all reports for one vessel land on the same partition and stay
 * ordered; that key has to be extracted at the publishing edge, since it isn't
 * available until the six-bit payload is decoded downstream.
 *
 * <p>When {@code produceSimulation} is set, the scripted {@link SimulationSource}
 * is run as a producer into the same topic, so {@code KAFKA} mode is a
 * self-contained demo that needs no external broker feed — only the broker.
 */
public final class KafkaSource implements AisSource {

    private static final Logger log = LoggerFactory.getLogger(KafkaSource.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);

    private final SourceProperties.Kafka config;
    private final SourceProperties.Simulation simulation;
    private final AtomicBoolean running = new AtomicBoolean();

    private KafkaConsumer<String, String> consumer;
    private Thread consumerThread;
    private SimulationSource producerBridge;
    private Producer<String, String> producer;

    public KafkaSource(SourceProperties.Kafka config, SourceProperties.Simulation simulation) {
        this.config = config;
        this.simulation = simulation;
    }

    @Override
    public void start(Consumer<TimestampedLine> sink) {
        running.set(true);
        if (config.produceSimulation()) {
            startProducerBridge();
        }
        consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(config.topic()));
        consumerThread = Thread.ofVirtual().name("kafka-source").start(() -> consumeLoop(sink));
        log.info("Kafka source consuming {} as group {} from {}",
                config.topic(), config.groupId(), config.bootstrapServers());
    }

    private void consumeLoop(Consumer<TimestampedLine> sink) {
        try {
            while (running.get()) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL_TIMEOUT)) {
                    String raw = record.value();
                    if (raw != null && !raw.isBlank()) {
                        sink.accept(new TimestampedLine(Instant.now(), raw));
                    }
                }
                consumer.commitAsync();
            }
        } catch (WakeupException expected) {
            // stop() was called during poll — a clean shutdown, not an error.
        } finally {
            try {
                consumer.commitSync(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // Best-effort final commit; nothing actionable on shutdown.
            }
            consumer.close();
        }
    }

    private void startProducerBridge() {
        producer = new KafkaProducer<>(producerProperties());
        producerBridge = new SimulationSource(simulation);
        producerBridge.start(line -> producer.send(new ProducerRecord<>(config.topic(), null, line.raw())));
        log.info("Kafka source also producing the scripted simulation into {}", config.topic());
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Live tail: a fresh instance follows current traffic rather than
        // replaying history. Restart-with-committed-offset still resumes exactly.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

    private Properties producerProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        return props;
    }

    @Override
    public void stop() {
        running.set(false);
        if (producerBridge != null) {
            producerBridge.stop();
        }
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
        if (consumer != null) {
            consumer.wakeup(); // unblocks poll() so the consumer thread exits promptly
        }
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    @Override
    public String describe() {
        return "kafka:" + config.bootstrapServers() + "/" + config.topic()
                + " group=" + config.groupId()
                + (config.produceSimulation() ? " (+simulation producer)" : "");
    }
}

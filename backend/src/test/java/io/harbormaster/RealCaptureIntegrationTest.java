package io.harbormaster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.harbormaster.ais.AisDecodeException;
import io.harbormaster.ais.AisDecoder;
import io.harbormaster.ais.PositionReport;
import io.harbormaster.nmea.FragmentAssembler;
import io.harbormaster.nmea.NmeaParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the bundled real-world capture (Norwegian Coastal Administration
 * open feed, NLOD license) through the full parse → assemble → decode path.
 *
 * <p>The geographic assertion is the point: every decoded position must fall
 * inside a generous Norwegian-coast bounding box. A sign flip, bit-offset
 * mistake or scaling error in the decoder would scatter vessels across the
 * globe and fail loudly here, in a way handcrafted vectors can't guarantee.
 */
class RealCaptureIntegrationTest {

    // The Norwegian network's real footprint: mainland coast plus the Arctic
    // stations — Bear Island and Svalbard traffic reaches past 77°N.
    private static final double LAT_MIN = 55.0;
    private static final double LAT_MAX = 82.0;
    private static final double LON_MIN = -2.0;
    private static final double LON_MAX = 35.0;

    @Test
    void bundledCaptureDecodesToNorwegianWaters() throws Exception {
        var mapper = new ObjectMapper();
        var assembler = new FragmentAssembler();
        int sentences = 0;
        int positions = 0;
        int outsideBox = 0;
        var outliers = new java.util.ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(getClass().getResourceAsStream("/data/ais-replay-sample.ndjson.gz")),
                StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String raw = mapper.readTree(line).path("raw").asText();
                var sentence = NmeaParser.parse(raw);
                assertThat(sentence).as("checksum-valid sentence: %s", raw).isPresent();
                sentences++;

                var assembled = assembler.offer(sentence.get(), Instant.now());
                if (assembled.isEmpty()) {
                    continue;
                }
                try {
                    var message = AisDecoder.decode(assembled.get().payload(), assembled.get().fillBits());
                    if (message instanceof PositionReport report && report.hasPosition()) {
                        positions++;
                        boolean inside = report.latitude() >= LAT_MIN && report.latitude() <= LAT_MAX
                                && report.longitude() >= LON_MIN && report.longitude() <= LON_MAX;
                        if (!inside) {
                            outsideBox++;
                            outliers.add("mmsi=%d lat=%.4f lon=%.4f".formatted(
                                    report.mmsi(), report.latitude(), report.longitude()));
                        }
                    }
                } catch (AisDecodeException e) {
                    // tolerated: real feeds contain the occasional truncated payload
                }
            }
        }

        assertThat(sentences).as("capture should contain a meaningful sample").isGreaterThan(100);
        assertThat(positions).as("capture should contain position reports").isGreaterThan(50);
        assertThat(outsideBox).as("decoded positions outside the Norwegian coast box: %s", outliers).isZero();
    }
}

package io.harbormaster.ais;

import io.harbormaster.nmea.FragmentAssembler;
import io.harbormaster.nmea.NmeaParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AisDecoderTest {

    /**
     * Golden vectors taken from the publicly documented AIVDM/AIVDO protocol
     * examples (GPSD project documentation), decoded independently by several
     * open-source implementations.
     */
    @Nested
    class PublishedGoldenVectors {

        @Test
        void decodesClassAPositionReport() {
            var sentence = NmeaParser.parse("!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C").orElseThrow();
            var message = AisDecoder.decode(sentence.payload(), sentence.fillBits());

            assertThat(message).isInstanceOfSatisfying(PositionReport.class, p -> {
                assertThat(p.type()).isEqualTo(1);
                assertThat(p.mmsi()).isEqualTo(477553000);
                assertThat(p.navStatus()).isEqualTo(5); // moored
                assertThat(p.speedOverGroundKn()).isEqualTo(0.0);
                assertThat(p.longitude()).isCloseTo(-122.345833, within(1e-6));
                assertThat(p.latitude()).isCloseTo(47.582833, within(1e-6));
                assertThat(p.courseOverGroundDeg()).isCloseTo(51.0, within(1e-9));
                assertThat(p.heading()).isEqualTo(181);
                assertThat(p.utcSecond()).isEqualTo(15);
            });
        }

        @Test
        void decodesTwoFragmentStaticVoyageData() {
            var assembler = new FragmentAssembler();
            var now = Instant.now();

            var first = NmeaParser
                    .parse("!AIVDM,2,1,3,B,55P5TL01VIaAL@7WKO@mBplU@<PDhh000000001S;AJ::4A80?4i@E53,0*3E")
                    .orElseThrow();
            assertThat(assembler.offer(first, now)).isEmpty();

            var second = NmeaParser.parse("!AIVDM,2,2,3,B,1@0000000000000,2*55").orElseThrow();
            var assembled = assembler.offer(second, now).orElseThrow();

            var message = AisDecoder.decode(assembled.payload(), assembled.fillBits());
            assertThat(message).isInstanceOfSatisfying(StaticVoyageData.class, s -> {
                assertThat(s.mmsi()).isEqualTo(369190000);
                assertThat(s.imoNumber()).isEqualTo(6710932);
                // MMSI 369190000 / IMO 6710932 / WDA9674 = research vessel "Mt. Mitchell",
                // a widely republished AIVDM documentation example.
                assertThat(s.callsign()).isEqualTo("WDA9674");
                assertThat(s.name()).isEqualTo("MT.MITCHELL");
                assertThat(s.shipType()).isEqualTo(99); // other
                assertThat(s.draughtM()).isCloseTo(6.0, within(1e-9));
                assertThat(s.destination()).isEqualTo("SEATTLE");
            });
        }
    }

    /**
     * Round-trip tests: fields are packed by an independent write-path
     * encoder and must come back identically through the decoder.
     */
    @Nested
    class RoundTrip {

        @Test
        void classAPositionSurvivesRoundTrip() {
            var encoded = new AisTestEncoder()
                    .unsigned(1, 6)              // type
                    .unsigned(0, 2)              // repeat
                    .unsigned(244660123, 30)     // MMSI
                    .unsigned(0, 4)              // nav status: under way
                    .signed(0, 8)                // rate of turn
                    .unsigned(147, 10)           // SOG 14.7 kn
                    .unsigned(1, 1)              // position accuracy
                    .signed(Math.round(4.297778 * 600_000), 28)   // lon (Rotterdam approach)
                    .signed(Math.round(51.984167 * 600_000), 27)  // lat
                    .unsigned(2735, 12)          // COG 273.5
                    .unsigned(274, 9)            // heading
                    .unsigned(43, 6)             // UTC second
                    .unsigned(0, 2)              // maneuver
                    .unsigned(0, 3)              // spare
                    .unsigned(0, 1)              // RAIM
                    .unsigned(0, 19)             // radio status
                    .build();

            var message = AisDecoder.decode(encoded.payload(), encoded.fillBits());

            assertThat(message).isInstanceOfSatisfying(PositionReport.class, p -> {
                assertThat(p.mmsi()).isEqualTo(244660123);
                assertThat(p.speedOverGroundKn()).isCloseTo(14.7, within(1e-9));
                assertThat(p.longitude()).isCloseTo(4.297778, within(1e-5));
                assertThat(p.latitude()).isCloseTo(51.984167, within(1e-5));
                assertThat(p.courseOverGroundDeg()).isCloseTo(273.5, within(1e-9));
                assertThat(p.heading()).isEqualTo(274);
                assertThat(p.utcSecond()).isEqualTo(43);
            });
        }

        @Test
        void notAvailableSentinelsBecomeNaN() {
            var encoded = new AisTestEncoder()
                    .unsigned(3, 6)
                    .unsigned(0, 2)
                    .unsigned(123456789, 30)
                    .unsigned(15, 4)             // nav status undefined
                    .signed(-128, 8)
                    .unsigned(1023, 10)          // SOG not available
                    .unsigned(0, 1)
                    .signed(181 * 600_000L, 28)  // lon not available
                    .signed(91 * 600_000L, 27)   // lat not available
                    .unsigned(3600, 12)          // COG not available
                    .unsigned(511, 9)            // heading not available
                    .unsigned(60, 6)
                    .unsigned(0, 2).unsigned(0, 3).unsigned(0, 1).unsigned(0, 19)
                    .build();

            var message = (PositionReport) AisDecoder.decode(encoded.payload(), encoded.fillBits());

            assertThat(message.hasPosition()).isFalse();
            assertThat(message.hasSpeed()).isFalse();
            assertThat(message.courseOverGroundDeg()).isNaN();
            assertThat(message.heading()).isEqualTo(-1);
        }

        @Test
        void classBPositionSurvivesRoundTrip() {
            var encoded = new AisTestEncoder()
                    .unsigned(18, 6)
                    .unsigned(0, 2)
                    .unsigned(265547250, 30)
                    .unsigned(0, 8)              // reserved
                    .unsigned(58, 10)            // SOG 5.8 kn
                    .unsigned(0, 1)
                    .signed(Math.round(11.832 * 600_000), 28)
                    .signed(Math.round(57.661 * 600_000), 27)
                    .unsigned(901, 12)           // COG 90.1
                    .unsigned(88, 9)
                    .unsigned(12, 6)
                    .unsigned(0, 29)             // flags + radio
                    .build();

            var message = AisDecoder.decode(encoded.payload(), encoded.fillBits());

            assertThat(message).isInstanceOfSatisfying(PositionReport.class, p -> {
                assertThat(p.type()).isEqualTo(18);
                assertThat(p.mmsi()).isEqualTo(265547250);
                assertThat(p.navStatus()).isEqualTo(-1);
                assertThat(p.speedOverGroundKn()).isCloseTo(5.8, within(1e-9));
                assertThat(p.longitude()).isCloseTo(11.832, within(1e-5));
                assertThat(p.latitude()).isCloseTo(57.661, within(1e-5));
                assertThat(p.heading()).isEqualTo(88);
            });
        }

        @Test
        void staticDataReportPartsDecodeIndependently() {
            var partA = new AisTestEncoder()
                    .unsigned(24, 6).unsigned(0, 2).unsigned(257000001, 30)
                    .unsigned(0, 2)              // part A
                    .string("NORDIC STAR", 120)
                    .build();
            var partB = new AisTestEncoder()
                    .unsigned(24, 6).unsigned(0, 2).unsigned(257000001, 30)
                    .unsigned(1, 2)              // part B
                    .unsigned(36, 8)             // ship type: sailing
                    .string("VENDOR!", 42)       // vendor id
                    .string("LJ5023", 42)        // callsign
                    .unsigned(0, 30)             // dimensions + spare
                    .build();

            var a = (StaticDataReport) AisDecoder.decode(partA.payload(), partA.fillBits());
            var b = (StaticDataReport) AisDecoder.decode(partB.payload(), partB.fillBits());

            assertThat(a.partNumber()).isZero();
            assertThat(a.name()).isEqualTo("NORDIC STAR");
            assertThat(b.partNumber()).isEqualTo(1);
            assertThat(b.shipType()).isEqualTo(36);
            assertThat(b.callsign()).isEqualTo("LJ5023");
        }
    }

    @Nested
    class Robustness {

        @Test
        void unsupportedTypesAreReportedNotDropped() {
            var encoded = new AisTestEncoder()
                    .unsigned(4, 6).unsigned(0, 2).unsigned(2320654, 30)
                    .unsigned(0, 130)
                    .build();

            var message = AisDecoder.decode(encoded.payload(), encoded.fillBits());

            assertThat(message).isInstanceOfSatisfying(UnsupportedMessage.class, u -> {
                assertThat(u.type()).isEqualTo(4);
                assertThat(u.mmsi()).isEqualTo(2320654);
            });
        }

        @Test
        void truncatedPayloadThrowsInsteadOfMisreading() {
            var encoded = new AisTestEncoder().unsigned(1, 6).unsigned(0, 2).unsigned(999, 30).build();

            assertThatThrownBy(() -> AisDecoder.decode(encoded.payload(), encoded.fillBits()))
                    .isInstanceOf(AisDecodeException.class)
                    .hasMessageContaining("too short");
        }

        @Test
        void invalidSixBitCharacterIsRejected() {
            assertThatThrownBy(() -> AisDecoder.decode("15Mv~", 0))
                    .isInstanceOf(AisDecodeException.class);
        }
    }
}

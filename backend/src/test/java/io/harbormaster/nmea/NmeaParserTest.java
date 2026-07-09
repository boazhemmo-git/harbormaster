package io.harbormaster.nmea;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NmeaParserTest {

    @Test
    void parsesWellFormedSentence() {
        var sentence = NmeaParser.parse("!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C").orElseThrow();

        assertThat(sentence.fragmentCount()).isEqualTo(1);
        assertThat(sentence.fragmentNumber()).isEqualTo(1);
        assertThat(sentence.sequenceId()).isEmpty();
        assertThat(sentence.channel()).isEqualTo("B");
        assertThat(sentence.payload()).isEqualTo("177KQJ5000G?tO`K>RA1wUbN0TKH");
        assertThat(sentence.fillBits()).isZero();
    }

    @Test
    void acceptsBaseStationTalker() {
        // Shore-station feeds (e.g. the Norwegian Coastal Administration) relay with BS talker
        assertThat(NmeaParser.parse("!BSVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*45")).isPresent();
    }

    @Test
    void stripsNmeaV4TagBlock() {
        var line = "\\s:2573135,c:1671620143*0B\\!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5C";

        assertThat(NmeaParser.parse(line)).isPresent();
    }

    @Test
    void rejectsCorruptedChecksum() {
        assertThat(NmeaParser.parse("!AIVDM,1,1,,B,177KQJ5000G?tO`K>RA1wUbN0TKH,0*5D")).isEmpty();
    }

    @Test
    void rejectsNonVdmSentences() {
        assertThat(NmeaParser.parse("$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")).isEmpty();
        assertThat(NmeaParser.parse("")).isEmpty();
        assertThat(NmeaParser.parse("garbage")).isEmpty();
    }
}

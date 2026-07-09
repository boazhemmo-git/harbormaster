package io.harbormaster.ingest;

import io.harbormaster.ais.AisEncoder;
import io.harbormaster.config.SourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Deterministic scenario generator for the zero-configuration demo: a fleet
 * of merchant, fishing and passenger traffic in the North Sea off the Dutch
 * coast, encoded into genuine AIVDM sentences so the entire wire pipeline
 * (checksum, fragmentation, six-bit decode) runs exactly as it would against
 * a live feed.
 *
 * <p>Four scripted actors reproduce the evasion patterns the detectors hunt:
 * a tanker that silences its transponder mid-transit, a pair holding station
 * alongside each other at sea, a vessel whose position teleports, and a
 * "fishing" vessel that claims underway while never moving. Simulated
 * vessels are flagged by their {@code SIM-} name prefix — no pretense of
 * being real traffic.
 */
public final class SimulationSource implements AisSource {

    private static final Logger log = LoggerFactory.getLogger(SimulationSource.class);
    private static final double KNOTS_TO_DEG_LAT_PER_SEC = 1.0 / 3600.0 / 60.0; // 1 kn ≈ 1 arc-min/h

    private final SourceProperties.Simulation config;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public SimulationSource(SourceProperties.Simulation config) {
        this.config = config;
    }

    /** One simulated transponder. */
    private static final class Actor {
        final int mmsi;
        final String name;
        final int shipType;
        final int reportEverySec;
        double lat;
        double lon;
        double sogKn;
        double cogDeg;
        int navStatus;
        boolean silent;
        int lengthBow = 80;
        int lengthStern = 20;

        Actor(int mmsi, String name, int shipType, double lat, double lon,
              double sogKn, double cogDeg, int navStatus, int reportEverySec) {
            this.mmsi = mmsi;
            this.name = name;
            this.shipType = shipType;
            this.lat = lat;
            this.lon = lon;
            this.sogKn = sogKn;
            this.cogDeg = cogDeg;
            this.navStatus = navStatus;
            this.reportEverySec = reportEverySec;
        }

        void advance(double dtSec) {
            double distDeg = sogKn * KNOTS_TO_DEG_LAT_PER_SEC * dtSec;
            double rad = Math.toRadians(cogDeg);
            lat += distDeg * Math.cos(rad);
            lon += distDeg * Math.sin(rad) / Math.cos(Math.toRadians(lat));
        }
    }

    @Override
    public void start(Consumer<TimestampedLine> sink) {
        running.set(true);
        worker = Thread.ofVirtual().name("simulation-source").start(() -> run(sink));
    }

    private void run(Consumer<TimestampedLine> sink) {
        Random random = new Random(config.seed());
        List<Actor> fleet = buildFleet(random);
        Actor darkTanker = fleet.get(0);
        Actor rendezvousA = fleet.get(1);
        Actor rendezvousB = fleet.get(2);
        Actor spoofer = fleet.get(3);

        log.info("Simulation started: {} vessels off the Dutch coast (seed {})", fleet.size(), config.seed());
        int tick = 0;
        int sequence = 0;
        try {
            while (running.get()) {
                tick++;
                for (Actor actor : fleet) {
                    if (!actor.silent) {
                        actor.advance(1.0);
                    }
                    // Staggered reporting: each actor transmits on its own cadence.
                    if (!actor.silent && tick % actor.reportEverySec == actor.mmsi % actor.reportEverySec) {
                        emitPosition(sink, actor, tick);
                    }
                    // Static/voyage data roughly every 2 minutes per vessel.
                    if (!actor.silent && tick % 120 == actor.mmsi % 120) {
                        emitStatic(sink, actor, sequence++);
                    }
                }
                script(tick, darkTanker, rendezvousA, rendezvousB, spoofer);
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The scripted anomaly timeline (seconds since start, repeating each 20 min). */
    private void script(int tick, Actor darkTanker, Actor rendezvousA, Actor rendezvousB, Actor spoofer) {
        int t = tick % 1200;

        // T+2:00 — the tanker goes dark mid-transit; T+8:00 it resurfaces 12 nm ahead.
        if (t == 120) {
            darkTanker.silent = true;
            log.info("SCENARIO: {} silencing transponder", darkTanker.name);
        }
        if (t == 480) {
            darkTanker.silent = false;
            double rad = Math.toRadians(darkTanker.cogDeg);
            double jump = 12.0 / 60.0; // 12 nm in degrees latitude
            darkTanker.lat += jump * Math.cos(rad);
            darkTanker.lon += jump * Math.sin(rad) / Math.cos(Math.toRadians(darkTanker.lat));
            log.info("SCENARIO: {} back on air 12 nm along track", darkTanker.name);
        }

        // T+1:00 — the pair slows to station-keeping ~150 m apart; T+12:00 they part.
        if (t == 60) {
            rendezvousB.lat = rendezvousA.lat + 0.0013;
            rendezvousB.lon = rendezvousA.lon;
            rendezvousA.sogKn = 0.4;
            rendezvousB.sogKn = 0.5;
            rendezvousA.cogDeg = 90;
            rendezvousB.cogDeg = 90;
            log.info("SCENARIO: {} and {} commencing rendezvous", rendezvousA.name, rendezvousB.name);
        }
        if (t == 720) {
            rendezvousA.sogKn = 11;
            rendezvousB.sogKn = 9;
            rendezvousA.cogDeg = 200;
            rendezvousB.cogDeg = 30;
        }

        // T+3:00 — the spoofer teleports 25 nm north.
        if (t == 180) {
            spoofer.lat += 25.0 / 60.0;
            log.info("SCENARIO: {} position jumped 25 nm", spoofer.name);
        }
    }

    private List<Actor> buildFleet(Random random) {
        List<Actor> fleet = new ArrayList<>();
        double lat0 = config.centerLat();
        double lon0 = config.centerLon();

        // Scripted actors first (indices matter).
        fleet.add(new Actor(244990001, "SIM NORDVIK TRADER", 81, lat0 + 0.15, lon0 - 0.4, 12.5, 45, 0, 3));
        fleet.add(new Actor(244990002, "SIM BALTIC PIONEER", 80, lat0 - 0.20, lon0 + 0.1, 8.0, 270, 0, 4));
        fleet.add(new Actor(244990003, "SIM SEA COURIER", 70, lat0 - 0.21, lon0 + 0.12, 9.0, 250, 0, 4));
        fleet.add(new Actor(244990004, "SIM WESTWIND", 70, lat0 + 0.05, lon0 + 0.3, 14.0, 310, 0, 3));
        // A loiterer: claims underway, never moves.
        fleet.add(new Actor(244990005, "SIM AURORA FISK", 30, lat0 + 0.28, lon0 + 0.05, 0.2, 180, 0, 5));

        String[][] lanes = {
                {"SIM MAAS CARRIER", "70"}, {"SIM ROTTE EXPRESS", "71"}, {"SIM DELTA FORTUNE", "80"},
                {"SIM NOORDZEE STAR", "70"}, {"SIM WADDEN RUNNER", "79"}, {"SIM SCHELDE GLORY", "82"},
                {"SIM HOLLAND SPIRIT", "70"}, {"SIM ZUIDER LIGHT", "89"}, {"SIM KUSTLIJN", "60"},
                {"SIM VLIELAND FERRY", "60"},
        };
        int mmsi = 244991000;
        for (int i = 0; i < config.vesselCount() - fleet.size(); i++) {
            String[] template = lanes[i % lanes.length];
            int shipType = Integer.parseInt(template[1]);
            boolean southbound = random.nextBoolean();
            double lat = lat0 + (random.nextDouble() - 0.5) * 1.2;
            double lon = lon0 + (random.nextDouble() - 0.5) * 1.6;
            double sog = switch (shipType / 10) {
                case 6 -> 18 + random.nextDouble() * 12;  // passenger
                case 3 -> 3 + random.nextDouble() * 3;    // fishing
                default -> 9 + random.nextDouble() * 7;   // cargo/tanker
            };
            double cog = southbound ? 195 + random.nextDouble() * 30 : 15 + random.nextDouble() * 30;
            int reportEvery = 2 + random.nextInt(6);
            var actor = new Actor(mmsi++, template[0] + " " + (i + 1), shipType,
                    lat, lon, sog, cog, 0, reportEvery);
            actor.lengthBow = 60 + random.nextInt(180);
            actor.lengthStern = 15 + random.nextInt(30);
            fleet.add(actor);
        }
        // A few vessels legitimately at anchor — quiet, slow, status 1.
        for (int i = 0; i < 5; i++) {
            fleet.add(new Actor(mmsi++, "SIM ANCHORAGE " + (i + 1), 70,
                    lat0 - 0.35 + i * 0.01, lon0 - 0.25, 0.1, 0, 1, 8));
        }
        return fleet;
    }

    private void emitPosition(Consumer<TimestampedLine> sink, Actor actor, int tick) {
        var sentences = AisEncoder.positionReport(
                actor.mmsi, actor.navStatus, actor.sogKn, actor.lat, actor.lon,
                actor.cogDeg, (int) Math.round(actor.cogDeg), tick % 60)
                .toSentences('A', 0);
        Instant now = Instant.now();
        sentences.forEach(s -> sink.accept(new TimestampedLine(now, s)));
    }

    private void emitStatic(Consumer<TimestampedLine> sink, Actor actor, int sequence) {
        var sentences = AisEncoder.staticVoyage(
                actor.mmsi, 9_000_000 + actor.mmsi % 1_000_000, "SIM" + actor.mmsi % 1000,
                actor.name, actor.shipType, actor.lengthBow, actor.lengthStern, 12, 12,
                8.5, "ROTTERDAM")
                .toSentences('A', sequence);
        Instant now = Instant.now();
        sentences.forEach(s -> sink.accept(new TimestampedLine(now, s)));
    }

    @Override
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public String describe() {
        return "simulation:north-sea seed=" + config.seed() + " vessels=" + config.vesselCount();
    }
}

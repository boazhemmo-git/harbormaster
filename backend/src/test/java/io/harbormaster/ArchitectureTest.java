package io.harbormaster;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural rules the compiler can't express. The headline rule: the
 * protocol core (NMEA + AIS packages) is plain Java — it must compile and
 * test without Spring on the classpath, so it stays reusable and its tests
 * stay millisecond-fast.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.harbormaster");
    }

    @Test
    void protocolCoreIsFrameworkFree() {
        noClasses()
                .that().resideInAnyPackage("io.harbormaster.ais..", "io.harbormaster.nmea..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "com.fasterxml.jackson..")
                .because("the protocol layer is a pure-Java library; frameworks stop at the pipeline boundary")
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnApi() {
        noClasses()
                .that().resideInAnyPackage(
                        "io.harbormaster.tracking..", "io.harbormaster.detection..",
                        "io.harbormaster.ais..", "io.harbormaster.nmea..", "io.harbormaster.ingest..")
                .should().dependOnClassesThat().resideInAPackage("io.harbormaster.api..")
                .because("presentation depends on the domain, never the reverse")
                .check(classes);
    }

    @Test
    void ingestDoesNotReachIntoTracking() {
        noClasses()
                .that().resideInAPackage("io.harbormaster.ingest..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.harbormaster.tracking..", "io.harbormaster.detection..")
                .because("sources only produce raw lines; the pipeline owns all downstream wiring")
                .check(classes);
    }
}

package at.hl7.testing.runner.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The module boundaries of docs/architecture.md, enforced. */
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.hl7.testing.runner");
    }

    @Test
    void coreIsFrameworkFreeAndVersionNeutral() {
        noClasses().that().resideInAPackage("at.hl7.testing.runner.core..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "ca.uhn.fhir..",
                        "org.hl7.fhir..", "ch.qos.logback..", "at.hl7.testing.runner.fhir..",
                        "at.hl7.testing.runner.validation..", "at.hl7.testing.runner.autoconfigure..",
                        "at.hl7.testing.runner.app..")
                .check(classes);
    }

    @Test
    void versionModulesDoNotKnowEachOther() {
        noClasses().that().resideInAPackage("at.hl7.testing.runner.fhir.r4..")
                .should().dependOnClassesThat().resideInAPackage("at.hl7.testing.runner.fhir.r5..").check(classes);
        noClasses().that().resideInAPackage("at.hl7.testing.runner.fhir.r5..")
                .should().dependOnClassesThat().resideInAPackage("at.hl7.testing.runner.fhir.r4..").check(classes);
        noClasses().that().resideInAPackage("at.hl7.testing.runner.fhir.hapi..")
                .should().dependOnClassesThat().resideInAnyPackage("org.hl7.fhir.r4..", "org.hl7.fhir.r5..")
                .check(classes);
    }

    @Test
    void adaptersDoNotDependOnSpring() {
        noClasses().that().resideInAnyPackage("at.hl7.testing.runner.fhir..", "at.hl7.testing.runner.validation..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..").check(classes);
    }

    @Test
    void corePackagesAreFreeOfCycles() {
        slices().matching("at.hl7.testing.runner.core.(*)..").should().beFreeOfCycles().check(classes);
    }

    /** Covers testrunner-test-support too: it is on this module's test class path and not test code itself. */
    @Test
    void noJdkSpecificOrInternalApis() {
        noClasses().that().resideInAPackage("at.hl7.testing.runner..")
                .should().dependOnClassesThat().resideInAnyPackage("com.sun..", "sun..", "jdk.internal..")
                .because("they are not part of Java SE and may be missing or change in other JDKs")
                .check(classes);
    }
}

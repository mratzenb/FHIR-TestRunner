package at.hl7.testing.runner.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
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

    /**
     * The public API of the core (A9 of docs/wiki/next-steps.md): what the adapters, the Spring modules and an
     * application embedding the runner may use. Everything else in the core may change without notice. A new use from
     * outside means adding the type here, deliberately, and to docs/wiki/modules/testrunner-core.md.
     */
    static final List<String> CORE_API_PACKAGES = List.of(
            "at.hl7.testing.runner.core.model..",   // the version-neutral TestScript model, filled by the version modules
            "at.hl7.testing.runner.core.config..",  // RunConfiguration and its records
            "at.hl7.testing.runner.core.spi..",     // the ports a module implements: FHIR versions, validator
            "at.hl7.testing.runner.core.report..");  // results and the ReportWriter port

    static final List<Class<?>> CORE_API_TYPES = List.of(
            at.hl7.testing.runner.core.engine.TestRunner.class,
            at.hl7.testing.runner.core.engine.TestRunners.class,
            at.hl7.testing.runner.core.engine.RunControl.class,
            at.hl7.testing.runner.core.engine.RunListener.class,
            at.hl7.testing.runner.core.engine.RunInfo.class,
            at.hl7.testing.runner.core.engine.ConfigurationException.class,
            at.hl7.testing.runner.core.http.HttpTransport.class,
            at.hl7.testing.runner.core.http.JdkHttpTransport.class,
            at.hl7.testing.runner.core.http.FhirRequest.class,
            at.hl7.testing.runner.core.http.FhirResponse.class,
            at.hl7.testing.runner.core.http.HttpHeaders.class);

    @Test
    void otherModulesUseOnlyThePublicApiOfTheCore() {
        DescribedPredicate<JavaClass> api = resideInAnyPackage(CORE_API_PACKAGES.toArray(String[]::new))
                .or(DescribedPredicate.describe("a type of the core API " + CORE_API_TYPES.stream()
                                .map(Class::getSimpleName).toList(),
                        type -> CORE_API_TYPES.stream().anyMatch(t -> t.getName().equals(type.getName())
                                || type.getName().startsWith(t.getName() + "$"))));
        noClasses().that().resideOutsideOfPackage("at.hl7.testing.runner.core..")
                .should().dependOnClassesThat(resideInAPackage("at.hl7.testing.runner.core..").and(not(api)))
                .check(classes);
    }

    @Test
    void listenersGetNoMutableRunState() {
        noClasses().that().resideOutsideOfPackage("at.hl7.testing.runner.core.engine..")
                .should().dependOnClassesThat().haveFullyQualifiedName("at.hl7.testing.runner.core.engine.RunContext")
                .check(classes);
    }

    @Test
    void corePackagesAreFreeOfCycles() {
        slices().matching("at.hl7.testing.runner.core.(*)..").should().beFreeOfCycles().check(classes);
    }
}

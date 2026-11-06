package at.hl7.testing.runner.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

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

    /**
     * The public API says where {@code null} is allowed: every parameter, return value and record component of a
     * reference type is {@code @Nonnull} or {@code @Nullable} (jakarta.annotation, ADR-0017). Absent values are
     * returned as {@code Optional} where that reads better; a null that is not declared is a bug.
     */
    @Test
    void publicApiDeclaresWhereNullIsAllowed() {
        List<String> missing = new ArrayList<>();
        for (JavaClass type : classes) {
            if (!isPublicApi(type)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (!isApiMember(method) || method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || method.getModifiers().contains(JavaModifier.BRIDGE) || isGenerated(type, method)) {
                    continue;
                }
                if (!method.getRawReturnType().isPrimitive() && !hasNullness(method.reflect().getAnnotations())) {
                    missing.add(method.getFullName() + ": return value");
                }
                checkParameters(method.reflect().getParameters(), method.getFullName(), missing);
            }
            for (JavaConstructor constructor : type.getConstructors()) {
                if (isApiMember(constructor) && !constructor.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                    checkParameters(constructor.reflect().getParameters(), constructor.getFullName(), missing);
                }
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.reflect().getRecordComponents()) {
                    if (!component.getType().isPrimitive() && !hasNullness(component.getAnnotations())) {
                        missing.add(type.getName() + "." + component.getName() + ": record component");
                    }
                }
            }
        }
        assertThat(missing).as("public API without @Nonnull or @Nullable").isEmpty();
    }

    /**
     * The public types of the API: the core API above, and every public type of the other modules – not the test
     * doubles of testrunner-test-support, whose test-jar the import finds on the class path.
     */
    private static boolean isPublicApi(JavaClass type) {
        if (!type.getModifiers().contains(JavaModifier.PUBLIC) || type.isAnnotation()
                || type.getPackageName().startsWith("at.hl7.testing.runner.testsupport")
                || type.getEnclosingClass().map(outer -> !outer.getModifiers().contains(JavaModifier.PUBLIC))
                        .orElse(false)) {
            return false;
        }
        if (!type.getPackageName().startsWith("at.hl7.testing.runner.core")) {
            return true;
        }
        return CORE_API_PACKAGES.stream().anyMatch(p -> type.getPackageName().startsWith(p.replace("..", "")))
                || CORE_API_TYPES.stream().anyMatch(t -> t.getName().equals(type.getName())
                        || type.getName().startsWith(t.getName() + "$"));
    }

    private static boolean isApiMember(JavaCodeUnit member) {
        return member.getModifiers().contains(JavaModifier.PUBLIC)
                || member.getModifiers().contains(JavaModifier.PROTECTED)
                || member.getOwner().isInterface() && !member.getModifiers().contains(JavaModifier.PRIVATE);
    }

    /**
     * What the compiler writes, not the source: {@code values} and {@code valueOf} of an enum, and {@code equals},
     * {@code hashCode} and {@code toString} of a record unless the record declares them itself (then they carry
     * their annotations like any other method).
     */
    private static boolean isGenerated(JavaClass type, JavaMethod method) {
        String name = method.getName();
        if (type.isEnum()) {
            return name.equals("values") || name.equals("valueOf");
        }
        boolean objectMethod = name.equals("equals") && method.getParameters().size() == 1
                || (name.equals("hashCode") || name.equals("toString")) && method.getParameters().isEmpty();
        return type.isRecord() && objectMethod && !hasNullness(method.reflect().getAnnotations())
                && Arrays.stream(method.reflect().getParameters()).noneMatch(p -> hasNullness(p.getAnnotations()));
    }

    private static void checkParameters(Parameter[] parameters, String owner, List<String> missing) {
        for (Parameter parameter : parameters) {
            if (!parameter.getType().isPrimitive() && !hasNullness(parameter.getAnnotations())) {
                missing.add(owner + ": parameter " + parameter.getName());
            }
        }
    }

    private static boolean hasNullness(Annotation[] annotations) {
        return Arrays.stream(annotations).anyMatch(a -> a instanceof Nonnull || a instanceof Nullable);
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

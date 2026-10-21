package at.hl7.testing.runner.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Settings of the profile validation ({@code assert.validateProfileId}) with the HL7 FHIR Validator CLI.
 *
 * @param enabled             default true; when false {@code validateProfileId} assertions are reported as warnings
 * @param mode                {@link ValidatorMode#PROCESS} (default): a process per validation;
 *                            {@link ValidatorMode#SERVER}: a long-running validator server, see {@link ServerModeConfig}
 * @param validatorJar        location of {@code validator_cli.jar}
 * @param javaExecutable      java used to start the validator; default the java running the runner
 * @param jvmArguments        arguments for the validator's JVM, e.g. {@code -Xmx4g}; both modes
 * @param terminologyServer   value of {@code -tx}; default {@code n/a} (no terminology checks)
 * @param locale              language of the validator messages, default {@code en}
 * @param timeout             maximum time of one validation, default 5 minutes
 * @param validateTestScripts whether every TestScript is validated against the base specification before it runs,
 *                            default true
 * @param igs                 further {@code -ig} arguments: package ids ({@code hl7.at.fhir.core.r4#2.0.0}), folders
 *                            or files; the profiles folder of the suite is always passed
 * @param extraArguments      further validator arguments passed as they are, in both modes
 * @param server              settings of the server mode
 */
public record ValidationConfig(Boolean enabled, ValidatorMode mode, Path validatorJar, Path javaExecutable,
                               List<String> jvmArguments, String terminologyServer, String locale, Duration timeout,
                               Boolean validateTestScripts, List<String> igs, List<String> extraArguments,
                               ServerModeConfig server) {

    public ValidationConfig {
        enabled = enabled == null || enabled;
        mode = mode == null ? ValidatorMode.PROCESS : mode;
        jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
        terminologyServer = terminologyServer == null || terminologyServer.isBlank() ? "n/a" : terminologyServer;
        locale = locale == null || locale.isBlank() ? "en" : locale;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        validateTestScripts = validateTestScripts == null || validateTestScripts;
        igs = igs == null ? List.of() : List.copyOf(igs);
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        server = server == null ? ServerModeConfig.defaults() : server;
    }

    /** All defaults; validation enabled, but the validator jar still has to be set. */
    public static ValidationConfig defaults() {
        return enabled(null);
    }

    /** Validation with the given validator jar and otherwise the defaults. */
    public static ValidationConfig enabled(Path validatorJar) {
        return new ValidationConfig(true, null, validatorJar, null, null, null, null, null, null, null, null, null);
    }

    public static ValidationConfig disabled() {
        return new ValidationConfig(false, null, null, null, null, null, null, null, false, null, null, null);
    }

    /** A copy with another mode. */
    public ValidationConfig withMode(ValidatorMode newMode) {
        return new ValidationConfig(enabled, newMode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, igs, extraArguments, server);
    }

    /** A copy with other server settings. */
    public ValidationConfig withServer(ServerModeConfig newServer) {
        return new ValidationConfig(enabled, mode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, igs, extraArguments, newServer);
    }

    /** A copy with other extra arguments. */
    public ValidationConfig withExtraArguments(List<String> newExtraArguments) {
        return new ValidationConfig(enabled, mode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, igs, newExtraArguments, server);
    }

    /** The java executable to start the validator with. */
    public Path resolvedJavaExecutable() {
        return javaExecutable != null ? javaExecutable : Path.of(System.getProperty("java.home"), "bin", "java");
    }
}

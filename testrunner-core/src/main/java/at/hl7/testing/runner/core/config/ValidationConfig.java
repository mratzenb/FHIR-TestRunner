package at.hl7.testing.runner.core.config;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Settings of the profile validation ({@code assert.validateProfileId}) with the HL7 FHIR Validator CLI.
 *
 * @param enabled             default true; when false {@code validateProfileId} assertions are reported as warnings
 * @param mode                {@link ValidatorMode#SERVER} (default): long-running validator servers, see
 *                            {@link ServerModeConfig}; {@link ValidatorMode#PROCESS}: a process per validation
 * @param validatorJar        location of {@code validator_cli.jar}
 * @param javaExecutable      java used to start the validator; default the java running the runner
 * @param jvmArguments        arguments for the validator's JVM, e.g. {@code -Xmx4g}; both modes
 * @param terminologyServer   value of {@code -tx}; default {@code n/a} (no terminology checks)
 * @param locale              language of the validator messages, default {@code en}
 * @param timeout             maximum time of one validation, default 5 minutes
 * @param validateTestScripts whether every TestScript is validated against the base specification before it runs,
 *                            default true
 * @param extraArguments      further validator arguments passed as they are, in both modes; the IGs to load are set
 *                            per suite ({@link SuiteConfig#profilesDir()}, {@link SuiteConfig#igs()})
 * @param server              settings of the server mode
 */
public record ValidationConfig(@Nonnull Boolean enabled, @Nonnull ValidatorMode mode, @Nullable Path validatorJar,
                               @Nullable Path javaExecutable, @Nonnull List<String> jvmArguments,
                               @Nonnull String terminologyServer, @Nonnull String locale, @Nonnull Duration timeout,
                               @Nonnull Boolean validateTestScripts, @Nonnull List<String> extraArguments,
                               @Nonnull ServerModeConfig server) {

    public ValidationConfig(@Nullable Boolean enabled, @Nullable ValidatorMode mode, @Nullable Path validatorJar,
                            @Nullable Path javaExecutable, @Nullable List<String> jvmArguments,
                            @Nullable String terminologyServer, @Nullable String locale, @Nullable Duration timeout,
                            @Nullable Boolean validateTestScripts, @Nullable List<String> extraArguments,
                            @Nullable ServerModeConfig server) {
        enabled = enabled == null || enabled;
        mode = mode == null ? ValidatorMode.SERVER : mode;
        jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
        terminologyServer = terminologyServer == null || terminologyServer.isBlank() ? "n/a" : terminologyServer;
        locale = locale == null || locale.isBlank() ? "en" : locale;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        validateTestScripts = validateTestScripts == null || validateTestScripts;
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        server = server == null ? ServerModeConfig.defaults() : server;
        this.enabled = enabled;
        this.mode = mode;
        this.validatorJar = validatorJar;
        this.javaExecutable = javaExecutable;
        this.jvmArguments = jvmArguments;
        this.terminologyServer = terminologyServer;
        this.locale = locale;
        this.timeout = timeout;
        this.validateTestScripts = validateTestScripts;
        this.extraArguments = extraArguments;
        this.server = server;
    }

    /** All defaults; validation enabled, but the validator jar still has to be set. */
    @Nonnull
    public static ValidationConfig defaults() {
        return enabled(null);
    }

    /** Validation with the given validator jar and otherwise the defaults. */
    @Nonnull
    public static ValidationConfig enabled(@Nonnull Path validatorJar) {
        return new ValidationConfig(true, null, validatorJar, null, null, null, null, null, null, null, null);
    }

    @Nonnull
    public static ValidationConfig disabled() {
        return new ValidationConfig(false, null, null, null, null, null, null, null, false, null, null);
    }

    /** A copy with another mode. */
    @Nonnull
    public ValidationConfig withMode(@Nonnull ValidatorMode newMode) {
        return new ValidationConfig(enabled, newMode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, extraArguments, server);
    }

    /** A copy with other server settings. */
    @Nonnull
    public ValidationConfig withServer(@Nonnull ServerModeConfig newServer) {
        return new ValidationConfig(enabled, mode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, extraArguments, newServer);
    }

    /** A copy with other extra arguments. */
    @Nonnull
    public ValidationConfig withExtraArguments(@Nonnull List<String> newExtraArguments) {
        return new ValidationConfig(enabled, mode, validatorJar, javaExecutable, jvmArguments, terminologyServer,
                locale, timeout, validateTestScripts, newExtraArguments, server);
    }

    /** The java executable to start the validator with. */
    @Nonnull
    public Path resolvedJavaExecutable() {
        return javaExecutable != null ? javaExecutable : Path.of(System.getProperty("java.home"), "bin", "java");
    }
}

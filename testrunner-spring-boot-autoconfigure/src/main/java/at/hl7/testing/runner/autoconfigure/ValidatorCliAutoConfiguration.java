package at.hl7.testing.runner.autoconfigure;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.validation.cli.ProfileValidators;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Uses the HL7 Validator CLI for profile validation when {@code testrunner-validation-cli} is on the classpath and
 * validation is enabled ({@code testrunner.validation.enabled}, default true).
 */
@AutoConfiguration(before = TestRunnerAutoConfiguration.class)
@ConditionalOnClass(ProfileValidators.class)
public class ValidatorCliAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProfileValidator profileValidator(RunConfiguration configuration) {
        return ProfileValidators.create(configuration.validation());
    }
}

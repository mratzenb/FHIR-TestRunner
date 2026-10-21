package at.hl7.testing.runner.app;

import at.hl7.testing.runner.core.config.RunConfiguration;
import at.hl7.testing.runner.core.spi.ProfileValidator;
import at.hl7.testing.runner.validation.cli.ProfileValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Warns at start-up when profile validation is enabled but the validator cannot run. */
@Component
class ValidatorCheck {

    private static final Logger log = LoggerFactory.getLogger(ValidatorCheck.class);

    private final ProfileValidator validator;
    private final RunConfiguration configuration;

    ValidatorCheck(ProfileValidator validator, RunConfiguration configuration) {
        this.validator = validator;
        this.configuration = configuration;
    }

    @EventListener(ApplicationStartedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void check() {
        if (validator.isEnabled() && validator != ProfileValidator.DISABLED) {
            ProfileValidators.problems(configuration.validation())
                    .forEach(problem -> log.warn("Profile validation will fail: {}", problem));
        }
    }
}

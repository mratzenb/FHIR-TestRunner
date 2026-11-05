package at.hl7.testing.runner.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunnerApplicationTest {

    @Test
    void translatesTheShortOptions() {
        assertThat(TestRunnerApplication.translate(new String[] {"--config", "run.yml", "--novalidator", "--x=y"}))
                .containsExactly("--spring.config.additional-location=file:run.yml",
                        "--testrunner.validation.enabled=false", "--x=y");
        assertThat(TestRunnerApplication.translate(new String[] {"--config=classpath:dev.yml"}))
                .containsExactly("--spring.config.additional-location=classpath:dev.yml");
        assertThat(TestRunnerApplication.translate(new String[] {"--check"})).containsExactly("--testrunner.mode=check");
    }
}

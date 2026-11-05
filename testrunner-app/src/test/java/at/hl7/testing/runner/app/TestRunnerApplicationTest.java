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
        assertThat(TestRunnerApplication.translate(new String[] {"--list"})).containsExactly("--testrunner.mode=list");
    }

    @Test
    void selectionOptionsAddUpToIndexedEntries() {
        assertThat(TestRunnerApplication.translate(new String[] {"--testscript=*patient*, *org*", "--test", "read",
                "--testscript", "Other", "--test=create-?", "--x=y"}))
                .containsExactly("--x=y",
                        "--testrunner.select.test-scripts[0]=*patient*",
                        "--testrunner.select.test-scripts[1]=*org*",
                        "--testrunner.select.test-scripts[2]=Other",
                        "--testrunner.select.tests[0]=read",
                        "--testrunner.select.tests[1]=create-?");
    }
}

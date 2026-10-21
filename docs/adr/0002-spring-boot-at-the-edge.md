# ADR-0002: Spring Boot 4 only at the edge; framework-free core

## Context
The brief asks for an externalizable configuration and "a pre-defined Spring configuration that can be
modified/extended", and to use Spring Boot 4 if it makes sense. The engine itself is a batch computation with
a handful of collaborators.

## Decision
* Spring Boot is used for what it is good at: externalized configuration, profiles, logging setup, wiring, CLI exit
  codes. It lives in `testrunner-spring-boot-autoconfigure` (an auto-configuration, every bean
  `@ConditionalOnMissingBean`) and `testrunner-app` (the `main`, `ApplicationRunner`, `ExitCodeGenerator`).
* The core uses constructor injection without annotations. `TestRunners.create(...)` wires it without Spring.
* `RunConfiguration` is bound with `Binder.get(env).bind("testrunner", RunConfiguration.class)` rather than
  `@ConfigurationProperties`, so the record needs no Spring annotation.

## Alternatives
* *No Spring* – a hand-written YAML/CLI layer would duplicate what Boot gives for free (env vars, profiles,
  relaxed binding, duration conversion) and offer no standard extension mechanism.
* *Spring throughout* (`@Component` engine classes) – ties the domain to the container, harder to embed (e.g. in a
  Maven plugin or a web service later) and to test.

## Consequences
* The runner can be embedded in any Java program, with or without Spring.
* Extension = define a bean (see configuration.md). Behaviour of the defaults is covered by
  `TestRunnerAutoConfigurationTest` with `ApplicationContextRunner`.
* The exec jar is ≈ 97 MB, mostly HAPI structures for two versions.

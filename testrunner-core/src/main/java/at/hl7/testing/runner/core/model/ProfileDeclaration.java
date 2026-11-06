package at.hl7.testing.runner.core.model;

import jakarta.annotation.Nonnull;

/**
 * A profile declared by a TestScript, referenced by {@code assert.validateProfileId}.
 *
 * <p>FHIR R4 declares profiles as {@code Reference} with an {@code id}, R5 as {@code canonical} with the id on
 * the primitive's element ({@code _profile}). Both end up here.
 *
 * @param id        the id {@code validateProfileId} refers to
 * @param canonical the canonical URL of the StructureDefinition
 */
public record ProfileDeclaration(@Nonnull String id, @Nonnull String canonical) {
}

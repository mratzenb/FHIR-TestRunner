package at.hl7.testing.runner.fhir.hapi;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseElement;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read access to an element of a HAPI FHIR model by element name, the same way for every FHIR version.
 *
 * <p>This is what lets one mapper read the TestScripts of all versions: it asks for {@code fixture},
 * {@code autocreate}, {@code operation} by name instead of calling version-specific getters. An element that does
 * not exist in a version (e.g. {@code stopTestOnFail} in R4) simply reads as absent.
 */
public final class ElementView {

    private final FhirContext context;
    private final BaseRuntimeElementDefinition<?> definition;
    private final IBase element;

    private ElementView(FhirContext context, BaseRuntimeElementDefinition<?> definition, IBase element) {
        this.context = context;
        this.definition = definition;
        this.element = element;
    }

    /** A view of a resource. */
    @Nonnull
    public static ElementView of(@Nonnull FhirContext context, @Nonnull IBaseResource resource) {
        return new ElementView(context, context.getResourceDefinition(resource), resource);
    }

    /** All values of a child element; empty if there are none or the element does not exist in this version. */
    @Nonnull
    public List<ElementView> children(@Nonnull String name) {
        if (!(definition instanceof BaseRuntimeElementCompositeDefinition<?> composite)) {
            return List.of();
        }
        BaseRuntimeChildDefinition child = composite.getChildByName(name);
        if (child == null) {
            return List.of();
        }
        List<ElementView> views = new ArrayList<>();
        for (IBase value : child.getAccessor().getValues(element)) {
            BaseRuntimeElementDefinition<?> childDefinition = child.getChildByName(name);
            if (childDefinition == null) {
                childDefinition = context.getElementDefinition(value.getClass());
            }
            views.add(new ElementView(context, childDefinition, value));
        }
        return views;
    }

    /** The first value of a child element. */
    @Nonnull
    public Optional<ElementView> child(@Nonnull String name) {
        return children(name).stream().findFirst();
    }

    /** The value of a primitive child as text, {@code null} if absent. */
    @Nullable
    public String string(@Nonnull String name) {
        return child(name).map(ElementView::primitiveValue).orElse(null);
    }

    /** The value of a boolean child, {@code defaultValue} if absent. */
    public boolean bool(@Nonnull String name, boolean defaultValue) {
        String value = string(name);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    /** Whether this element is a primitive, e.g. a {@code canonical}. */
    public boolean isPrimitive() {
        return element instanceof IPrimitiveType<?>;
    }

    /** The value of this primitive as text, {@code null} if it is no primitive or has no value. */
    @Nullable
    public String primitiveValue() {
        return element instanceof IPrimitiveType<?> primitive ? primitive.getValueAsString() : null;
    }

    /**
     * The element id ({@code "id"} of a backbone or data type element, or {@code _name.id} of a primitive), which
     * TestScripts use to name fixtures and profiles.
     */
    @Nullable
    public String elementId() {
        return element instanceof IBaseElement e ? e.getId() : null;
    }

    /** The FHIR type name of this element, e.g. {@code Reference} or {@code canonical}. */
    @Nonnull
    public String typeName() {
        return definition.getName();
    }
}

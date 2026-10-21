package at.hl7.testing.runner.fhir.hapi;

import ca.uhn.fhir.parser.IParserErrorHandler;
import ca.uhn.fhir.parser.json.BaseJsonLikeValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects what HAPI's parser finds instead of failing on the first problem. Unknown elements are content this
 * version ignores (warnings); values of the wrong type or invalid values make the content unusable (errors).
 */
final class CollectingErrorHandler implements IParserErrorHandler {

    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    @Override
    public void containedResourceWithNoId(IParseLocation location) {
        warnings.add("contained resource without id" + at(location));
    }

    @Override
    public void incorrectJsonType(IParseLocation location, String elementName, BaseJsonLikeValue.ValueType expected,
                                  BaseJsonLikeValue.ScalarType expectedScalar, BaseJsonLikeValue.ValueType found,
                                  BaseJsonLikeValue.ScalarType foundScalar) {
        errors.add("'" + elementName + "'" + at(location) + " must be " + describe(expected, expectedScalar)
                + ", but is " + describe(found, foundScalar));
    }

    @Override
    public void invalidValue(IParseLocation location, String value, String error) {
        errors.add("invalid value '" + value + "'" + at(location) + ": " + error);
    }

    @Override
    public void missingRequiredElement(IParseLocation location, String elementName) {
        // cardinality is checked by the validator; the runner itself copes with absent elements
    }

    @Override
    public void unexpectedRepeatingElement(IParseLocation location, String elementName) {
        errors.add("'" + elementName + "'" + at(location) + " must not repeat");
    }

    @Override
    public void unknownAttribute(IParseLocation location, String attributeName) {
        warnings.add("unknown attribute '" + attributeName + "'" + at(location));
    }

    @Override
    public void unknownElement(IParseLocation location, String elementName) {
        warnings.add("unknown element '" + elementName + "'" + at(location));
    }

    @Override
    public void unknownReference(IParseLocation location, String reference) {
        // references to resources outside the TestScript are normal
    }

    @Override
    public void invalidInternalReference(IParseLocation location, String reference) {
        warnings.add("invalid internal reference '" + reference + "'" + at(location));
    }

    @Override
    public void extensionContainsValueAndNestedExtensions(IParseLocation location) {
        errors.add("extension with both a value and nested extensions" + at(location));
    }

    private static String at(IParseLocation location) {
        return location == null || location.getParentElementName() == null ? "" : " in " + location.getParentElementName();
    }

    private static String describe(BaseJsonLikeValue.ValueType type, BaseJsonLikeValue.ScalarType scalar) {
        if (type == null) {
            return "?";
        }
        return scalar == null ? type.name().toLowerCase() : type.name().toLowerCase() + " " + scalar.name().toLowerCase();
    }
}

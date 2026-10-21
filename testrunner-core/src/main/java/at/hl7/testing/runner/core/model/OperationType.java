package at.hl7.testing.runner.core.model;

import java.util.Locale;

/**
 * The kind of a TestScript operation ({@code operation.type.code}).
 *
 * <p>FHIR R4 takes the codes from {@code testscript-operation-codes}, R5 from {@code restful-interaction}; the
 * codes for the RESTful interactions are the same in both. Codes the runner does not know map to {@link #OTHER},
 * such an operation needs an explicit {@code method} and {@code url} or {@code params}.
 */
public enum OperationType {
    READ, VREAD, UPDATE, PATCH, DELETE, HISTORY, CREATE, SEARCH, CAPABILITIES, TRANSACTION, BATCH, OPERATION, OTHER;

    /** The operation type for a code such as {@code read}; {@code null} for {@code null}, {@link #OTHER} if unknown. */
    public static OperationType fromCode(String code) {
        if (code == null) {
            return null;
        }
        return switch (code.trim().toLowerCase(Locale.ROOT)) {
            case "read" -> READ;
            case "vread" -> VREAD;
            case "update", "updatecreate" -> UPDATE;
            case "patch" -> PATCH;
            case "delete" -> DELETE;
            case "history", "history-instance", "history-type", "history-system" -> HISTORY;
            case "create" -> CREATE;
            case "search", "search-type", "search-system", "search-compartment" -> SEARCH;
            case "capabilities" -> CAPABILITIES;
            case "transaction" -> TRANSACTION;
            case "batch" -> BATCH;
            case "operation" -> OPERATION;
            default -> OTHER;
        };
    }

    /** The HTTP method a request of this type uses if the operation does not name one, or {@code null} if unknown. */
    public HttpMethod defaultMethod() {
        return switch (this) {
            case READ, VREAD, HISTORY, SEARCH, CAPABILITIES -> HttpMethod.GET;
            case UPDATE -> HttpMethod.PUT;
            case PATCH -> HttpMethod.PATCH;
            case DELETE -> HttpMethod.DELETE;
            case CREATE, TRANSACTION, BATCH, OPERATION -> HttpMethod.POST;
            case OTHER -> null;
        };
    }
}

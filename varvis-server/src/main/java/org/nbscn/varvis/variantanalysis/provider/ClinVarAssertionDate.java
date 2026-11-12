package org.nbscn.varvis.variantanalysis.provider;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Normalizes only the two source formats; callers retain the complete original assertion. */
final class ClinVarAssertionDate {
    private static final DateTimeFormatter SOURCE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT);
    private ClinVarAssertionDate() { }
    static String iso(String value) {
        if (value == null) return "";
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(value).toString();
            if (value.matches("[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4}")) return LocalDate.parse(value, SOURCE).toString();
        } catch (DateTimeParseException invalid) { return ""; }
        return "";
    }
}

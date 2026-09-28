package com.example.pipeline.etl;

import com.example.pipeline.PipelineProperties.Columns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.function.Function;

/** Transform step for a single row: validates and cleans it, or throws with the rejection reason. */
public class RowValidator {

    private final Columns columns;
    private final DateTimeFormatter dateFormat;

    public RowValidator(Columns columns, String datePattern) {
        this.columns = columns;
        // STRICT rejects dates like 2024-02-30; it needs 'u' (year) instead of 'y' (year-of-era)
        this.dateFormat = DateTimeFormatter.ofPattern(datePattern.replace('y', 'u')).withResolverStyle(ResolverStyle.STRICT);
    }

    /** @param field returns the raw value for a header name */
    public SaleRecord parse(Function<String, String> field) {
        for (String column : columns.all()) {
            String value = field.apply(column);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing " + column);
            }
        }

        LocalDate date;
        try {
            date = LocalDate.parse(field.apply(columns.saleDate()).trim(), dateFormat);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid " + columns.saleDate());
        }

        int quantity;
        try {
            quantity = Integer.parseInt(field.apply(columns.quantity()).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(columns.quantity() + " is not a whole number");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(columns.quantity() + " must be positive");
        }

        BigDecimal price;
        try {
            price = new BigDecimal(field.apply(columns.unitPrice()).trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(columns.unitPrice() + " is not a number");
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException(columns.unitPrice() + " is negative");
        }

        return new SaleRecord(
                field.apply(columns.saleId()).trim(),
                date,
                field.apply(columns.productName()).trim(),
                field.apply(columns.category()).trim(),
                quantity,
                price,
                field.apply(columns.paymentMethod()).trim().toUpperCase(Locale.ROOT));
    }
}

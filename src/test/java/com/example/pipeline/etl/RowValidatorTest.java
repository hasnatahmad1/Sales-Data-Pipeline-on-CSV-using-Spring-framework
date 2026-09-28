package com.example.pipeline.etl;

import com.example.pipeline.PipelineProperties.Columns;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowValidatorTest {

    private final RowValidator validator = new RowValidator(
            new Columns("sale_id", "sale_date", "product_name", "category", "quantity", "unit_price", "payment_method"),
            "yyyy-MM-dd");

    private Map<String, String> row() {
        Map<String, String> row = new HashMap<>();
        row.put("sale_id", " S-1 ");
        row.put("sale_date", "2026-09-01");
        row.put("product_name", "Latte");
        row.put("category", "Coffee");
        row.put("quantity", "3");
        row.put("unit_price", "4.2");
        row.put("payment_method", "card");
        return row;
    }

    @Test
    void parsesAndCleansValidRow() {
        SaleRecord s = validator.parse(row()::get);
        assertEquals("S-1", s.saleId());
        assertEquals(LocalDate.of(2026, 9, 1), s.saleDate());
        assertEquals("CARD", s.paymentMethod());
        assertEquals(new BigDecimal("12.60"), s.amount());
    }

    @Test
    void rejectsBadRows() {
        assertReason("missing product_name", "product_name", " ");
        assertReason("quantity must be positive", "quantity", "-2");
        assertReason("quantity is not a whole number", "quantity", "two");
        assertReason("unit_price is negative", "unit_price", "-1.00");
        assertReason("invalid sale_date", "sale_date", "2026-02-30");
        assertReason("invalid sale_date", "sale_date", "01/09/2026");
    }

    private void assertReason(String expected, String column, String value) {
        Map<String, String> row = row();
        row.put(column, value);
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> validator.parse(row::get)).getMessage());
    }
}

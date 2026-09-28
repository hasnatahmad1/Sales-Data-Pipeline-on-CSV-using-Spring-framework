package com.example.pipeline.etl;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A validated, cleaned sales row. */
public record SaleRecord(String saleId, LocalDate saleDate, String productName, String category,
                         int quantity, BigDecimal unitPrice, String paymentMethod) {

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}

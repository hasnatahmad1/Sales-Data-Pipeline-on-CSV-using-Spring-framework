package com.example.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/** Everything under "pipeline.*" in application.properties. */
@ConfigurationProperties("pipeline")
public record PipelineProperties(Path inputDir, Path processedDir, Path rejectedDir, String dateFormat, Columns columns) {

    /** CSV header names for each field. */
    public record Columns(String saleId, String saleDate, String productName, String category,
                          String quantity, String unitPrice, String paymentMethod) {

        public List<String> all() {
            return List.of(saleId, saleDate, productName, category, quantity, unitPrice, paymentMethod);
        }
    }
}

package com.example.pipeline.web;

import com.example.pipeline.etl.ReportRepository;
import com.example.pipeline.etl.ReportRepository.CategorySales;
import com.example.pipeline.etl.ReportRepository.DailySales;
import com.example.pipeline.etl.ReportRepository.PaymentMethodSales;
import com.example.pipeline.etl.ReportRepository.ProductSales;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportRepository reports;

    public ReportController(ReportRepository reports) {
        this.reports = reports;
    }

    @GetMapping("/daily")
    public List<DailySales> daily() {
        return reports.daily();
    }

    @GetMapping("/top-products")
    public List<ProductSales> topProducts(@RequestParam(defaultValue = "10") int limit) {
        return reports.topProducts(Math.clamp(limit, 1, 1000));
    }

    @GetMapping("/by-category")
    public List<CategorySales> byCategory() {
        return reports.byCategory();
    }

    @GetMapping("/by-payment-method")
    public List<PaymentMethodSales> byPaymentMethod() {
        return reports.byPaymentMethod();
    }
}

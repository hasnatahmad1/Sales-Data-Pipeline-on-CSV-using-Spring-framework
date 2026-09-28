package com.example.pipeline.etl;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregates. They are rebuilt from the de-duplicated "sales" table after every run,
 * so re-running never double counts.
 */
@Repository
public class ReportRepository {

    public record DailySales(LocalDate saleDate, BigDecimal totalSales, int transactions, int unitsSold) {
    }

    public record ProductSales(String productName, String category, int unitsSold, BigDecimal revenue) {
    }

    public record CategorySales(String category, BigDecimal totalSales, int transactions, int unitsSold) {
    }

    public record PaymentMethodSales(String paymentMethod, BigDecimal totalSales, int transactions) {
    }

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ponytail: full rebuild each run is simple and always correct; switch to incremental upserts if it gets slow
    @Transactional
    public void rebuild() {
        jdbc.update("DELETE FROM report_daily_sales");
        jdbc.update("""
                INSERT INTO report_daily_sales (sale_date, total_sales, transactions, units_sold)
                SELECT sale_date, SUM(amount), COUNT(*), SUM(quantity) FROM sales GROUP BY sale_date""");

        jdbc.update("DELETE FROM report_top_products");
        jdbc.update("""
                INSERT INTO report_top_products (product_name, category, units_sold, revenue)
                SELECT product_name, MAX(category), SUM(quantity), SUM(amount) FROM sales GROUP BY product_name""");

        jdbc.update("DELETE FROM report_sales_by_category");
        jdbc.update("""
                INSERT INTO report_sales_by_category (category, total_sales, transactions, units_sold)
                SELECT category, SUM(amount), COUNT(*), SUM(quantity) FROM sales GROUP BY category""");

        jdbc.update("DELETE FROM report_sales_by_payment_method");
        jdbc.update("""
                INSERT INTO report_sales_by_payment_method (payment_method, total_sales, transactions)
                SELECT payment_method, SUM(amount), COUNT(*) FROM sales GROUP BY payment_method""");
    }

    public List<DailySales> daily() {
        return jdbc.query("SELECT * FROM report_daily_sales ORDER BY sale_date",
                new DataClassRowMapper<>(DailySales.class));
    }

    public List<ProductSales> topProducts(int limit) {
        return jdbc.query("SELECT * FROM report_top_products ORDER BY units_sold DESC, revenue DESC LIMIT ?",
                new DataClassRowMapper<>(ProductSales.class), limit);
    }

    public List<CategorySales> byCategory() {
        return jdbc.query("SELECT * FROM report_sales_by_category ORDER BY total_sales DESC",
                new DataClassRowMapper<>(CategorySales.class));
    }

    public List<PaymentMethodSales> byPaymentMethod() {
        return jdbc.query("SELECT * FROM report_sales_by_payment_method ORDER BY total_sales DESC",
                new DataClassRowMapper<>(PaymentMethodSales.class));
    }
}

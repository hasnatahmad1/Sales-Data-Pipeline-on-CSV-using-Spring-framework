package com.example.pipeline.etl;

import com.example.pipeline.PipelineProperties;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Extract CSV files -> transform (validate/clean) -> load into H2, then rebuild reports. */
@Service
public class PipelineService {

    public record PipelineRun(long id, LocalDateTime startedAt, LocalDateTime finishedAt, String status,
                              int filesProcessed, int rowsLoaded, int rowsRejected, String message) {
    }

    private record FileResult(int loaded, int rejected) {
    }

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final PipelineProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReportRepository reports;
    private final RowValidator validator;

    public PipelineService(PipelineProperties props, JdbcTemplate jdbc, TransactionTemplate tx, ReportRepository reports) {
        this.props = props;
        this.jdbc = jdbc;
        this.tx = tx;
        this.reports = reports;
        this.validator = new RowValidator(props.columns(), props.dateFormat());
    }

    @Scheduled(fixedDelayString = "${pipeline.schedule-interval}", initialDelayString = "PT5S")
    public void scheduledRun() {
        run();
    }

    /** One pipeline run over every CSV in the input folder. Synchronized so manual and scheduled runs never overlap. */
    public synchronized PipelineRun run() {
        LocalDateTime started = LocalDateTime.now();
        long runId = insertRun(started);
        int files = 0, loaded = 0, rejected = 0;
        List<String> errors = new ArrayList<>();
        String status;
        try {
            Files.createDirectories(props.inputDir());
            Files.createDirectories(props.processedDir());
            Files.createDirectories(props.rejectedDir());

            for (Path file : listInputFiles()) {
                try {
                    FileResult r = processFile(file);
                    files++;
                    loaded += r.loaded();
                    rejected += r.rejected();
                } catch (Exception e) {
                    log.error("File {} failed", file.getFileName(), e);
                    errors.add(file.getFileName() + ": " + e.getMessage());
                    moveTo(file, props.rejectedDir());
                }
            }
            reports.rebuild();
            status = errors.isEmpty() ? "SUCCESS" : "PARTIAL";
        } catch (Exception e) {
            log.error("Pipeline run failed", e);
            errors.add(e.getMessage());
            status = "FAILED";
        }
        String message = errors.isEmpty() ? null : String.join("; ", errors);
        if (message != null && message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        jdbc.update("""
                UPDATE pipeline_runs SET finished_at = ?, status = ?, files_processed = ?, rows_loaded = ?,
                rows_rejected = ?, message = ? WHERE id = ?""",
                Timestamp.valueOf(LocalDateTime.now()), status, files, loaded, rejected, message, runId);
        if (files > 0 || !errors.isEmpty()) {
            log.info("Run {} {}: {} file(s), {} loaded, {} rejected", runId, status, files, loaded, rejected);
        }
        return findRun(runId);
    }

    public List<PipelineRun> findRuns() {
        return jdbc.query("SELECT * FROM pipeline_runs ORDER BY id DESC", new DataClassRowMapper<>(PipelineRun.class));
    }

    private PipelineRun findRun(long id) {
        return jdbc.queryForObject("SELECT * FROM pipeline_runs WHERE id = ?", new DataClassRowMapper<>(PipelineRun.class), id);
    }

    private long insertRun(LocalDateTime started) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pipeline_runs (started_at, status) VALUES (?, 'RUNNING')", Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.valueOf(started));
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    private List<Path> listInputFiles() throws IOException {
        try (Stream<Path> s = Files.list(props.inputDir())) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .toList();
        }
    }

    // ponytail: reads the whole file into memory; stream it if files grow beyond a few hundred MB
    private FileResult processFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String hash = sha256(bytes);
        String fileName = file.getFileName().toString();

        Integer seenBefore = jdbc.queryForObject("SELECT COUNT(*) FROM processed_files WHERE file_hash = ?", Integer.class, hash);
        if (seenBefore > 0) {
            log.info("{} was already processed (same content), skipping", fileName);
            moveTo(file, props.processedDir());
            return new FileResult(0, 0);
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1); // Excel BOM
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true)
                .get();

        List<SaleRecord> good = new ArrayList<>();
        List<List<String>> bad = new ArrayList<>();
        List<String> header;
        try (CSVParser parser = format.parse(new StringReader(text))) {
            header = parser.getHeaderNames();
            Set<String> headerLower = new HashSet<>();
            header.forEach(h -> headerLower.add(h.toLowerCase(Locale.ROOT)));
            List<String> missing = props.columns().all().stream()
                    .filter(c -> !headerLower.contains(c.toLowerCase(Locale.ROOT))).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("missing column(s) " + missing + " in header " + header);
            }

            Set<String> idsInFile = new HashSet<>();
            for (CSVRecord record : parser) {
                String reason;
                if (!record.isConsistent()) {
                    reason = "wrong number of fields";
                } else {
                    try {
                        SaleRecord sale = validator.parse(record::get);
                        if (!idsInFile.add(sale.saleId())) {
                            reason = "duplicate sale_id in file";
                        } else if (alreadyLoaded(sale.saleId())) {
                            reason = "duplicate sale_id (already loaded)";
                        } else {
                            good.add(sale);
                            continue;
                        }
                    } catch (IllegalArgumentException e) {
                        reason = e.getMessage();
                    }
                }
                List<String> row = new ArrayList<>(record.toList());
                row.add(reason);
                bad.add(row);
            }
        }

        // Load: rows + "file processed" marker in one transaction
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> {
            // MERGE = upsert on sale_id, a second safety net for idempotency
            jdbc.batchUpdate("""
                    MERGE INTO sales (sale_id, sale_date, product_name, category, quantity, unit_price, amount,
                                      payment_method, source_file, loaded_at)
                    KEY (sale_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    good, 500, (ps, s) -> {
                        ps.setString(1, s.saleId());
                        ps.setObject(2, s.saleDate());
                        ps.setString(3, s.productName());
                        ps.setString(4, s.category());
                        ps.setInt(5, s.quantity());
                        ps.setBigDecimal(6, s.unitPrice());
                        ps.setBigDecimal(7, s.amount());
                        ps.setString(8, s.paymentMethod());
                        ps.setString(9, fileName);
                        ps.setTimestamp(10, Timestamp.valueOf(now));
                    });
            jdbc.update("INSERT INTO processed_files (file_hash, file_name, processed_at) VALUES (?, ?, ?)",
                    hash, fileName, Timestamp.valueOf(now));
        });

        if (!bad.isEmpty()) {
            writeRejected(fileName, header, bad);
        }
        moveTo(file, props.processedDir());
        log.info("{}: {} loaded, {} rejected", fileName, good.size(), bad.size());
        return new FileResult(good.size(), bad.size());
    }

    // ponytail: one lookup per row; load the id set up front if files get large
    private boolean alreadyLoaded(String saleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE sale_id = ?", Integer.class, saleId) > 0;
    }

    private void writeRejected(String fileName, List<String> header, List<List<String>> rows) throws IOException {
        List<String> headerWithReason = new ArrayList<>(header);
        headerWithReason.add("reason");
        Path out = props.rejectedDir().resolve(baseName(fileName) + "_rejected_" + LocalDateTime.now().format(STAMP) + ".csv");
        try (Writer w = Files.newBufferedWriter(out);
             CSVPrinter printer = CSVFormat.DEFAULT.builder().setHeader(headerWithReason.toArray(String[]::new)).get().print(w)) {
            printer.printRecords(rows);
        }
    }

    /** Moves a file into a folder, adding a timestamp if a file with that name is already there. */
    private void moveTo(Path file, Path dir) {
        try {
            Path target = dir.resolve(file.getFileName());
            if (Files.exists(target)) {
                String name = file.getFileName().toString();
                target = dir.resolve(baseName(name) + "_" + LocalDateTime.now().format(STAMP) + name.substring(baseName(name).length()));
            }
            Files.move(file, target);
        } catch (IOException e) {
            log.error("Could not move {} to {}", file, dir, e);
        }
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

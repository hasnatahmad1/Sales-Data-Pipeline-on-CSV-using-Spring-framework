# sales-data-pipeline

A small ETL pipeline in Spring Boot 4 (Java 25) that loads sales CSV files into an embedded H2 database
and serves aggregated reports over REST.

```
data/input/*.csv ──► Extract ──► Transform (validate, clean, de-duplicate) ──► Load (H2) ──► rebuild reports
                                        │                                         │
                                        └─► data/rejected/<file>_rejected_<time>.csv   data/processed/<file>
```

## Prerequisites
- JDK 25
- Maven 3.6.3+

No MySQL, Docker or anything else: the database is an H2 file created at `data/pipeline.db.mv.db`.

## Run
```bash
mvn clean package
java -jar target/sales-data-pipeline.jar
```
Run from the project folder (all paths are relative to it). The API listens on `http://localhost:8080`.

To try it with the sample data (a fresh clone has an empty `data/input/`):
```bash
cp samples/*.csv data/input/
```
The samples produce 27 loaded rows and 9 rejected rows.
The pipeline runs 5 seconds after startup and then every minute (`pipeline.schedule-interval`), or on demand
with `POST /api/pipeline/run`.

## CSV format
First line is a header. Columns can be in any order and header matching is case-insensitive.

| column           | example            | rule                                  |
|------------------|--------------------|---------------------------------------|
| `sale_id`        | `S-1001`           | required, unique across all files     |
| `sale_date`      | `2026-09-01`       | required, valid date in `pipeline.date-format` |
| `product_name`   | `Wireless Mouse`   | required (quote it if it has commas)  |
| `category`       | `Electronics`      | required                              |
| `quantity`       | `2`                | required, whole number > 0            |
| `unit_price`     | `24.99`            | required, number ≥ 0                  |
| `payment_method` | `Card`             | required, stored upper-case (`CARD`)  |

Values are trimmed; a UTF-8 BOM (Excel) is fine.

### Different headers?
Map your header names in `src/main/resources/application.properties` (or override at startup):
```properties
pipeline.columns.sale-id=OrderID
pipeline.columns.sale-date=Date
pipeline.date-format=dd/MM/yyyy
```
```bash
java -jar target/sales-data-pipeline.jar --pipeline.columns.sale-id=OrderID --pipeline.date-format=dd/MM/yyyy
```

## What happens to a file
1. **Extract**: every `*.csv` in `data/input/` is read.
2. **Transform**: each row is validated. Rejected rows: missing fields, quantity ≤ 0 or not a number, negative or
   non-numeric price, invalid dates (including `2026-09-31`), duplicate `sale_id` in the file or already loaded earlier.
3. **Load**: valid rows are upserted into `sales` (key `sale_id`) in one transaction per file. Rejected rows are
   written to `data/rejected/<file>_rejected_<timestamp>.csv` with an extra `reason` column. The file is moved to
   `data/processed/`. A file with a missing required column is moved to `data/rejected/` as-is, and the run is `PARTIAL`.
4. **Reports**: the four report tables are rebuilt from `sales`.

### Idempotency
- `sale_id` is the primary key of `sales` and rows are written with `MERGE ... KEY(sale_id)`, so a sale can never be stored twice.
- Every loaded file's SHA-256 is stored in `processed_files`; dropping the exact same file again is skipped.
- Reports are recomputed from `sales`, never incremented, so re-runs cannot double count.

## Folder layout
```
sales-data-pipeline/
├── samples/                 3 sample CSVs (with some intentionally bad rows)
├── data/                    created at runtime (git-ignored except data/input/)
│   ├── input/               drop CSV files here
│   ├── processed/           files that were loaded
│   ├── rejected/            rejected rows (+ reason) and unreadable files
│   └── pipeline.db.mv.db    H2 database
└── src/main/java/com/example/pipeline
    ├── SalesPipelineApplication.java
    ├── PipelineProperties.java        pipeline.* settings
    ├── etl/RowValidator.java          transform: validate and clean one row
    ├── etl/PipelineService.java       extract, load, file moves, run tracking, scheduler
    ├── etl/ReportRepository.java      aggregate tables and report queries
    └── web/                           REST controllers
```

## Tables
| table | contents |
|-------|----------|
| `sales` | clean rows (sale_id PK, amount = quantity × unit_price, source_file) |
| `processed_files` | file hash, name, time |
| `pipeline_runs` | id, started_at, finished_at, status (`RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED`), files_processed, rows_loaded, rows_rejected, message |
| `report_daily_sales` | sale_date, total_sales, transactions, units_sold |
| `report_top_products` | product_name, category, units_sold, revenue |
| `report_sales_by_category` | category, total_sales, transactions, units_sold |
| `report_sales_by_payment_method` | payment_method, total_sales, transactions |

## API
```bash
# trigger a run now (returns the run record)
curl -X POST http://localhost:8080/api/pipeline/run

# run history, newest first
curl http://localhost:8080/api/pipeline/runs

# reports
curl http://localhost:8080/api/reports/daily
curl "http://localhost:8080/api/reports/top-products?limit=5"   # default 10
curl http://localhost:8080/api/reports/by-category
curl http://localhost:8080/api/reports/by-payment-method
```
On Windows PowerShell use `curl.exe` (or `Invoke-RestMethod -Method Post http://localhost:8080/api/pipeline/run`).

Example `GET /api/reports/by-payment-method` after loading the samples:
```json
[{"paymentMethod":"CARD","totalSales":849.87,"transactions":14},
 {"paymentMethod":"MOBILE","totalSales":280.17,"transactions":6},
 {"paymentMethod":"CASH","totalSales":204.69,"transactions":7}]
```

## Settings (`application.properties`)
| property | default |
|----------|---------|
| `pipeline.input-dir` / `processed-dir` / `rejected-dir` | `data/input`, `data/processed`, `data/rejected` |
| `pipeline.schedule-interval` | `PT1M` (ISO-8601 duration: `PT30S`, `PT5M`, `PT1H`) |
| `pipeline.date-format` | `yyyy-MM-dd` |
| `pipeline.columns.*` | the header names above |
| `server.port` | `8080` |

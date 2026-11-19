package io.joblab.worker;

import io.joblab.messaging.JobProtocol.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ReportGenerator {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_LENGTH = 524_288;
    private final JdbcTemplate jdbc;

    public ReportGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String generate(Claim job) {
        return switch (job.type()) {
            case DAILY_TOTALS -> totals(job);
            case SALES_DETAIL -> detail(job);
        };
    }

    private String totals(Claim job) {
        return jdbc.query("""
                SELECT sale_date, COUNT(*) AS orders, SUM(quantity) AS units, SUM(quantity * unit_price) AS amount
                FROM sale WHERE tenant_id = ? AND sale_date BETWEEN ? AND ?
                GROUP BY sale_date ORDER BY sale_date
                """, rows -> {
            var csv = new StringBuilder("sale_date,orders,units,amount\r\n");
            while (rows.next()) {
                csv.append(rows.getDate("sale_date")).append(',')
                        .append(rows.getLong("orders")).append(',')
                        .append(rows.getLong("units")).append(',')
                        .append(rows.getBigDecimal("amount").toPlainString()).append("\r\n");
            }
            return csv.toString();
        }, job.tenantId().toString(), job.fromDate(), job.toDate());
    }

    private String detail(Claim job) {
        return jdbc.query("""
                SELECT id, sale_date, sku, quantity, unit_price
                FROM sale WHERE tenant_id = ? AND sale_date BETWEEN ? AND ?
                ORDER BY sale_date, id LIMIT ?
                """, this::writeDetails, job.tenantId().toString(), job.fromDate(), job.toDate(), MAX_ROWS + 1);
    }

    private String writeDetails(ResultSet rows) throws SQLException {
        var csv = new StringBuilder("id,sale_date,sku,quantity,unit_price\r\n");
        int count = 0;
        while (rows.next()) {
            if (++count > MAX_ROWS) {
                throw new IllegalArgumentException("Report exceeds 5000 sales rows");
            }
            csv.append(rows.getLong("id")).append(',')
                    .append(rows.getDate("sale_date")).append(',')
                    .append(csvText(rows.getString("sku"))).append(',')
                    .append(rows.getInt("quantity")).append(',')
                    .append(rows.getBigDecimal("unit_price").toPlainString()).append("\r\n");
            if (csv.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("Report exceeds the output length limit");
            }
        }
        return csv.toString();
    }

    static String csvText(String value) {
        if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

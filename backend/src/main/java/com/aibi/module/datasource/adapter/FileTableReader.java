package com.aibi.module.datasource.adapter;

import com.aibi.common.exception.BizException;
import com.aibi.common.exception.ErrorCode;
import com.aibi.module.datasource.DataSourceType;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件类数据源解析器（CSV / Excel / Parquet）：把<b>每个工作表</b>的首行作为列名、其余作为数据行，流式回调给调用方。
 *
 * <p>被两处复用：① {@code CsvFileAdapter} 载入 SQLite 临时库（一张工作表建一张表，边读边批插，内存友好）；
 * ② 上传接口读取每张表的前 N 行做预览，让用户勾选要导入哪些工作表。列统一按字符串处理（与既有 CSV 骨架一致，
 * 不做类型推断）；空列名 / 重复列名会被规范化，避免建表冲突。
 *
 * <p>CSV 无工作表概念，统一视为<b>单表</b>（表名 {@link #CSV_SHEET}、下标 0），使两种文件类型共用同一条链路，
 * 调用方无需分支处理。Excel（.xlsx/.xls）用 Apache POI {@link WorkbookFactory} 读取；受上传大小上限约束，
 * 超大文件建议改用 CSV。Parquet 用 Apache Parquet 的 {@code AvroParquetReader} 读成 {@code GenericRecord}，
 * 列名取自 schema、值统一转字符串（嵌套结构序列化为 JSON 串、二进制按十六进制），同样复用文件链路。
 *
 * <p><b>无表头的工作表（封面页 / 说明页 / 空表）不抛异常</b>：{@link #listSheets} 返回它但 {@code headers} 为空，
 * {@link #readSheets} 直接跳过。一张说明页不该毁掉整个工作簿的导入。
 */
@Component
public class FileTableReader {

    private static final Logger log = LoggerFactory.getLogger(FileTableReader.class);

    /** CSV 无工作表概念时对外呈现的唯一表名（与 {@code CsvFileAdapter} 单表建表名保持一致）。 */
    public static final String CSV_SHEET = "data";

    /**
     * 多表流式回调：切换工作表时先收到 {@link #onTable}（含规范化后的表头），随后逐行收到该表数据，
     * 该表读完收到 {@link #onTableEnd}（供调用方收尾提交）。方法允许抛出受检异常，由 {@link #readSheets} 统一包装。
     */
    public interface SheetHandler {
        void onTable(String sheetName, int sheetIndex, List<String> headers) throws Exception;

        void onRow(List<String> row) throws Exception;

        void onTableEnd() throws Exception;
    }

    /**
     * 工作表概要（{@link #listSheets} 的产物，只读表头不读数据行）。
     *
     * @param name    工作表名（CSV 恒为 {@link #CSV_SHEET}；Parquet 亦为 {@link #CSV_SHEET}）
     * @param index   在工作簿中的下标（CSV / Parquet 恒为 0）
     * @param headers 规范化后的列名；<b>为空表示该表没有可导入的表头</b>，调用方应跳过
     */
    public record SheetInfo(String name, int index, List<String> headers) {

        /** 是否具备可导入的表结构。 */
        public boolean importable() {
            return headers != null && !headers.isEmpty();
        }
    }

    /**
     * 单个工作表的预览。
     *
     * @param columns   规范化后的列名；为空表示该工作表无表头（前端置灰、不可勾选）
     * @param rows      前 N 行数据
     * @param truncated 是否因超过预览上限而截断（还有更多行）
     */
    public record SheetPreview(String name, int index, List<String> columns,
                               List<List<String>> rows, boolean truncated) {

        /** 是否具备可导入的表结构。 */
        public boolean importable() {
            return columns != null && !columns.isEmpty();
        }
    }

    /**
     * 列出全部工作表及其表头（一次打开文件），供导入前决定建几张表、哪些结构相同可合并。
     *
     * <p>返回顺序与工作簿一致；无表头的工作表也在列表里（{@code headers} 为空），由调用方按
     * {@link SheetInfo#importable()} 自行跳过。
     */
    public List<SheetInfo> listSheets(Path file, DataSourceType type) {
        if (type == DataSourceType.EXCEL) {
            return excelSheets(file);
        }
        if (type == DataSourceType.PARQUET) {
            return parquetSheets(file);
        }
        return List.of(new SheetInfo(CSV_SHEET, 0, csvHeaders(file)));
    }

    /**
     * 流式读取指定工作表；{@code sheetIndexes} 为空表示读取全部<b>有表头</b>的工作表。
     *
     * @param maxRowsPerSheet 每张表最多回调的数据行数；{@code <=0} 表示不限制
     * @return 累计回调的数据行数
     */
    public int readSheets(Path file, DataSourceType type, List<Integer> sheetIndexes,
                          int maxRowsPerSheet, SheetHandler handler) {
        if (type == DataSourceType.EXCEL) {
            return readExcelSheets(file, sheetIndexes, maxRowsPerSheet, handler);
        }
        if (type == DataSourceType.PARQUET) {
            return readParquetSheets(file, maxRowsPerSheet, handler);
        }
        return readCsvSheets(file, sheetIndexes, maxRowsPerSheet, handler);
    }

    /**
     * 读取全部工作表的预览（上传后展示清单，供用户勾选导入范围）。
     *
     * <p>无表头的工作表也保留在返回列表里（{@code columns} 为空），前端据此置灰并提示"已跳过"，
     * 避免用户以为文件没解析出来。
     */
    public List<SheetPreview> previewAll(Path file, DataSourceType type, int maxRows) {
        int limit = maxRows <= 0 ? 20 : maxRows;
        List<SheetInfo> infos = listSheets(file, type);
        List<Integer> wanted = infos.stream().filter(SheetInfo::importable).map(SheetInfo::index).toList();
        PreviewCollector collector = new PreviewCollector(limit);
        if (!wanted.isEmpty()) {
            readSheets(file, type, wanted, limit + 1, collector);
        }
        Map<Integer, SheetPreview> byIndex = new HashMap<>();
        for (SheetPreview p : collector.result()) {
            byIndex.put(p.index(), p);
        }
        List<SheetPreview> out = new ArrayList<>(infos.size());
        for (SheetInfo info : infos) {
            SheetPreview hit = byIndex.get(info.index());
            out.add(hit != null ? hit
                    : new SheetPreview(info.name(), info.index(), List.of(), List.of(), false));
        }
        return out;
    }

    // ---------- CSV ----------

    private int readCsvSheets(Path file, List<Integer> sheetIndexes, int maxRows, SheetHandler handler) {
        if (!selects(sheetIndexes, 0)) {
            return 0;
        }
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (CSVParser parser = format.parse(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            List<String> headers = sanitize(parser.getHeaderNames());
            if (headers.isEmpty()) {
                log.info("CSV 无表头，跳过: {}", file);
                return 0;
            }
            handler.onTable(CSV_SHEET, 0, headers);
            int count = 0;
            for (CSVRecord record : parser) {
                if (maxRows > 0 && count >= maxRows) {
                    break;
                }
                List<String> row = new ArrayList<>(record.size());
                for (int i = 0; i < record.size(); i++) {
                    row.add(record.get(i));
                }
                handler.onRow(row);
                count++;
            }
            handler.onTableEnd();
            return count;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "CSV 解析失败: " + rootMessage(e));
        }
    }

    /** 只取 CSV 表头（不读数据行）；解析失败返回空列表，由调用方判定为不可导入。 */
    private List<String> csvHeaders(Path file) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (CSVParser parser = format.parse(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            return sanitize(parser.getHeaderNames());
        } catch (Exception e) {
            log.warn("CSV 表头读取失败: {} - {}", file, rootMessage(e));
            return List.of();
        }
    }

    // ---------- Excel ----------

    private List<SheetInfo> excelSheets(Path file) {
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            DataFormatter formatter = new DataFormatter();
            List<SheetInfo> out = new ArrayList<>(workbook.getNumberOfSheets());
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                out.add(new SheetInfo(workbook.getSheetName(i), i,
                        sheetHeaders(workbook.getSheetAt(i), formatter)));
            }
            return out;
        } catch (Exception e) {
            log.warn("Excel 工作表清单读取失败: {} - {}", file, rootMessage(e));
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "Excel 解析失败: " + rootMessage(e));
        }
    }

    private int readExcelSheets(Path file, List<Integer> sheetIndexes, int maxRows, SheetHandler handler) {
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "Excel 无工作表");
            }
            DataFormatter formatter = new DataFormatter();
            int total = 0;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (!selects(sheetIndexes, i)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(i);
                String name = workbook.getSheetName(i);
                List<String> headers = sheetHeaders(sheet, formatter);
                if (headers.isEmpty()) {
                    log.info("跳过无表头的工作表: {} (#{} of {})", name, i, file.getFileName());
                    continue;
                }
                total += readSheetRows(sheet, name, i, headers, maxRows, formatter, handler);
            }
            return total;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Excel 解析失败: {} - {}", file, rootMessage(e));
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "Excel 解析失败: " + rootMessage(e));
        }
    }

    /** 读某工作表的表头行；首行为空（封面 / 说明页 / 空表）返回空列表而非抛异常。 */
    private List<String> sheetHeaders(Sheet sheet, DataFormatter formatter) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            return List.of();
        }
        int colCount = headerRow.getLastCellNum();
        if (colCount <= 0) {
            return List.of();
        }
        List<String> raw = new ArrayList<>(colCount);
        boolean blank = true;
        for (int c = 0; c < colCount; c++) {
            Cell cell = headerRow.getCell(c);
            String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
            if (!value.isEmpty()) {
                blank = false;
            }
            raw.add(value);
        }
        return blank ? List.of() : sanitize(raw);
    }

    /** 逐行回调单个工作表的数据（整行为空的跳过），收尾时通知 {@code onTableEnd}。 */
    private int readSheetRows(Sheet sheet, String name, int index, List<String> headers,
                              int maxRows, DataFormatter formatter, SheetHandler handler) throws Exception {
        handler.onTable(name, index, headers);
        int colCount = headers.size();
        int count = 0;
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            if (maxRows > 0 && count >= maxRows) {
                break;
            }
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<String> cells = new ArrayList<>(colCount);
            boolean blank = true;
            for (int c = 0; c < colCount; c++) {
                Cell cell = row.getCell(c);
                String value = cell == null ? "" : formatter.formatCellValue(cell);
                if (!value.isBlank()) {
                    blank = false;
                }
                cells.add(value);
            }
            if (blank) {
                continue;   // 跳过整行为空
            }
            handler.onRow(cells);
            count++;
        }
        handler.onTableEnd();
        return count;
    }

    // ---------- Parquet ----------

    /** Parquet 表结构：单表（{@link #CSV_SHEET}），列名取自 Avro schema。 */
    private List<SheetInfo> parquetSheets(Path file) {
        try (ParquetReader<GenericRecord> reader = openParquet(file)) {
            GenericRecord first = reader.read();
            if (first == null) {
                return List.of(new SheetInfo(CSV_SHEET, 0, List.of()));   // 空 parquet：无表头
            }
            return List.of(new SheetInfo(CSV_SHEET, 0, schemaFields(first.getSchema())));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Parquet 表头读取失败: {} - {}", file, rootMessage(e));
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "Parquet 解析失败: " + rootMessage(e));
        }
    }

    /** 流式读 Parquet：单表，逐行回调（值转字符串）。 */
    private int readParquetSheets(Path file, int maxRows, SheetHandler handler) {
        try (ParquetReader<GenericRecord> reader = openParquet(file)) {
            GenericRecord rec = reader.read();
            if (rec == null) {
                handler.onTable(CSV_SHEET, 0, List.of());
                handler.onTableEnd();
                return 0;
            }
            List<String> headers = schemaFields(rec.getSchema());
            handler.onTable(CSV_SHEET, 0, headers);
            int count = 0;
            do {
                if (maxRows > 0 && count >= maxRows) {
                    break;
                }
                handler.onRow(rowValues(rec));
                count++;
            } while ((rec = reader.read()) != null);
            handler.onTableEnd();
            return count;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Parquet 解析失败: {} - {}", file, rootMessage(e));
            throw new BizException(ErrorCode.DATASOURCE_CONNECT_FAILED, "Parquet 解析失败: " + rootMessage(e));
        }
    }

    private ParquetReader<GenericRecord> openParquet(Path file) throws IOException {
        // 纯 NIO 打开（ParquetInputFile），不碰 Hadoop FileSystem —— 否则 Windows 本地会因缺少 HADOOP_HOME 崩溃。
        return AvroParquetReader.<GenericRecord>builder(new ParquetInputFile(file)).build();
    }

    private static List<String> schemaFields(org.apache.avro.Schema schema) {
        List<String> out = new ArrayList<>(schema.getFields().size());
        for (org.apache.avro.Schema.Field f : schema.getFields()) {
            out.add(f.name());
        }
        return out;
    }

    /** Parquet 记录 → 字符串行（列按 schema 顺序）。嵌套/复杂类型序列化为 JSON 串；二进制按十六进制。 */
    private static List<String> rowValues(GenericRecord rec) {
        List<String> out = new ArrayList<>(rec.getSchema().getFields().size());
        for (org.apache.avro.Schema.Field f : rec.getSchema().getFields()) {
            out.add(valueToString(rec.get(f.name())));
        }
        return out;
    }

    private static String valueToString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof GenericRecord) {
            return v.toString();   // 嵌套结构：Avro 自带 JSON 形式
        }
        if (v instanceof java.nio.ByteBuffer bb) {
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();   // 二进制/decimal 按十六进制（极少出现，避免崩溃）
        }
        return String.valueOf(v);
    }

    // ---------- 工具 ----------

    /** 选择集为空表示"全部"；否则按显式下标判断是否读取该工作表。 */
    private static boolean selects(List<Integer> sheetIndexes, int index) {
        return sheetIndexes == null || sheetIndexes.isEmpty() || sheetIndexes.contains(index);
    }

    /** 规范化列名：去空白、空列名补 {@code col_N}、重复列名追加序号，避免建表列名冲突。 */
    private List<String> sanitize(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null ? "" : raw.get(i).trim();
            if (h.isEmpty()) {
                h = "col_" + (i + 1);
            }
            String base = h;
            int n = 2;
            while (!seen.add(h)) {
                h = base + "_" + n++;
            }
            out.add(h);
        }
        return out;
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /** 预览采集器：一次遍历收集每张表的前 N 行（多读一行以判定 truncated）。 */
    private static final class PreviewCollector implements SheetHandler {
        private final int limit;
        private final List<SheetPreview> out = new ArrayList<>();
        private String name;
        private int index;
        private List<String> columns;
        private List<List<String>> rows;
        private boolean truncated;

        PreviewCollector(int limit) {
            this.limit = limit;
        }

        @Override
        public void onTable(String sheetName, int sheetIndex, List<String> headers) {
            flush();
            name = sheetName;
            index = sheetIndex;
            columns = headers;
            rows = new ArrayList<>();
            truncated = false;
        }

        @Override
        public void onRow(List<String> row) {
            if (rows.size() < limit) {
                rows.add(row);
            } else {
                truncated = true;
            }
        }

        @Override
        public void onTableEnd() {
            flush();
        }

        List<SheetPreview> result() {
            flush();
            return out;
        }

        private void flush() {
            if (columns != null) {
                out.add(new SheetPreview(name, index, columns, rows, truncated));
            }
            columns = null;
        }
    }
}

package com.example.ostpick;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读取公开 Google Sheet，导出 CSV 后按行提取 B 列单号和 D 列库位，不写入远程表格。 */
public final class GoogleSheetImporter {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ROWS = 50000;
    private static final int MAX_FIELD_CHARS = 100000;
    public static final class SheetRow {
        public final String number;
        public final String location;
        public SheetRow(String number, String location) {
            this.number = number;
            this.location = location;
        }
    }
    private GoogleSheetImporter() {}

    public static URL buildExportUrl(String address) throws IOException {
        try {
            URI uri = new URI(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"docs.google.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IOException("请填写 https://docs.google.com/spreadsheets/d/ 开头的表格链接");
            }
            Matcher matcher = Pattern.compile(
                    "^/spreadsheets/d/([A-Za-z0-9_-]{20,})(?:/.*)?$")
                    .matcher(uri.getPath());
            if (!matcher.matches()) throw new IOException("无法识别 Google Sheet 表格编号");
            String gid = parameter(uri.getRawQuery(), "gid");
            if (gid == null) gid = parameter(uri.getRawFragment(), "gid");
            if (gid == null) gid = "0";
            if (!gid.matches("[0-9]{1,20}")) throw new IOException("工作表 gid 格式不正确");
            return new URL("https://docs.google.com/spreadsheets/d/"
                    + matcher.group(1) + "/export?format=csv&gid=" + gid);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("表格地址格式不正确", error);
        }
    }

    private static String parameter(String raw, String name) throws Exception {
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (name.equals(URLDecoder.decode(parts[0], "UTF-8"))) {
                return parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
            }
        }
        return null;
    }

    public static List<SheetRow> downloadRows(String address) throws IOException {
        URL url = buildExportUrl(address);
        long deadline = System.nanoTime() + 90_000_000_000L;
        for (int redirect = 0; redirect <= 5; redirect++) {
            ensureDeadline(deadline);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || !(host.equals("docs.google.com")
                    || host.endsWith(".googleusercontent.com"))) {
                throw new IOException("表格要求登录或跳转到不支持的地址，请检查公开访问权限");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "text/csv,text/plain;q=0.9,*/*;q=0.1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303
                        || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("表格下载跳转地址为空");
                    url = new URL(url, location);
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new IOException("表格不可公开下载，请设置为知道链接的任何人可查看，并允许下载");
                }
                if (status == 404) throw new IOException("未找到表格或工作表，请检查链接和 gid");
                if (status == 429) throw new IOException("请求过于频繁，请稍后重试");
                if (status != 200) throw new IOException("下载失败，HTTP " + status);
                String type = connection.getContentType();
                if (type != null && type.toLowerCase(Locale.ROOT).contains("text/html")) {
                    throw new IOException("返回了登录或网页内容，未取得 CSV，请检查公开访问权限");
                }
                if (connection.getContentLength() > MAX_BYTES) {
                    throw new IOException("表格超过 10 MB，请缩小数据范围");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        ensureDeadline(deadline);
                        if (output.size() + size > MAX_BYTES) {
                            throw new IOException("表格超过 10 MB，请缩小数据范围");
                        }
                        output.write(buffer, 0, size);
                    }
                }
                String csv = new String(output.toByteArray(), StandardCharsets.UTF_8);
                String beginning = csv.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
                if (beginning.startsWith("<!doctype html") || beginning.startsWith("<html")) {
                    throw new IOException("未取得 CSV，服务器返回了网页内容");
                }
                return parseRows(csv);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("下载跳转次数过多，请检查表格地址");
    }

    private static void ensureDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
        if (System.nanoTime() > deadline) throw new IOException("下载超时，请检查网络后重试");
    }

    /** 支持引号、引号转义、逗号、单元格内换行、CRLF 和 UTF-8 BOM。 */
    static List<SheetRow> parseRows(String csv) throws IOException {
        List<SheetRow> result = new ArrayList<>();
        PushbackReader reader = new PushbackReader(new StringReader(csv), 1);
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        boolean rowStarted = false;
        boolean first = true;
        int value;
        while ((value = reader.read()) != -1) {
            char c = (char) value;
            if (first) {
                first = false;
                if (c == '\uFEFF') continue;
            }
            if (quoted) {
                if (c == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else {
                        quoted = false;
                        afterQuote = true;
                        if (next != -1) reader.unread(next);
                    }
                } else field.append(c);
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                rowStarted = true;
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                appendRow(result, row);
                row.clear();
                field.setLength(0);
                afterQuote = false;
                rowStarted = false;
                if (c == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) reader.unread(next);
                }
            } else if (c == '"') {
                if (field.length() != 0 || afterQuote) throw new IOException("CSV 引号格式异常");
                quoted = true;
                rowStarted = true;
            } else {
                if (afterQuote) throw new IOException("CSV 引号结束后出现异常字符");
                field.append(c);
                rowStarted = true;
            }
            if (field.length() > MAX_FIELD_CHARS) throw new IOException("表格包含过长单元格");
        }
        if (quoted) throw new IOException("CSV 内容不完整，引号未闭合");
        if (rowStarted || !row.isEmpty() || field.length() > 0 || afterQuote) {
            row.add(field.toString());
            appendRow(result, row);
        }
        return result;
    }

    private static void appendRow(List<SheetRow> result, List<String> row) throws IOException {
        if (result.size() >= MAX_ROWS) throw new IOException("表格超过 50000 行，请分批导入");
        result.add(new SheetRow(row.size() >= 2 ? row.get(1) : "",
                row.size() >= 4 ? row.get(3) : ""));
    }
}

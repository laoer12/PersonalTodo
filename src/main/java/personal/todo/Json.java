package personal.todo;

import java.util.*;

/** Small strict JSON codec; no external libraries or network dependencies. */
final class Json {
    static Object parse(String source) {
        Parser p = new Parser(source); Object value = p.value(0); p.space();
        if (p.at != source.length()) throw p.error("多余的内容");
        return value;
    }
    static String write(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString();
    }
    private static void append(StringBuilder b, Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) b.append(value);
        else if (value instanceof String text) {
            b.append('"');
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                    default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
                }
            }
            b.append('"');
        } else if (value instanceof Map<?, ?> map) {
            b.append('{'); boolean first = true;
            for (var e : map.entrySet()) { if (!first) b.append(','); first = false; append(b, e.getKey().toString()); b.append(':'); append(b, e.getValue()); }
            b.append('}');
        } else if (value instanceof Iterable<?> list) {
            b.append('['); boolean first = true;
            for (Object item : list) { if (!first) b.append(','); first = false; append(b, item); }
            b.append(']');
        } else throw new IllegalArgumentException("不支持的 JSON 类型");
    }
    private static final class Parser {
        final String text; int at;
        Parser(String text) { this.text = text; }
        IllegalArgumentException error(String message) { return new IllegalArgumentException("JSON 格式错误，位置 " + at + "：" + message); }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        Object value(int depth) {
            if (depth > 64) throw error("嵌套过深"); space();
            if (at >= text.length()) throw error("意外结束");
            char c = text.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do { space(); if (at >= text.length() || text.charAt(at) != '"') throw error("缺少字段名");
                    String key = string(); if (!take(':')) throw error("缺少冒号");
                    if (map.containsKey(key)) throw error("重复字段"); map.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw error("缺少右大括号"); return map;
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>(); if (take(']')) return list;
                do { list.add(value(depth + 1)); } while (take(','));
                if (!take(']')) throw error("缺少右方括号"); return list;
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, at)) { at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            }
            int start = at;
            if (at < text.length() && text.charAt(at) == '-') at++;
            if (at >= text.length() || !Character.isDigit(text.charAt(at))) throw error("无效值");
            if (text.charAt(at) == '0') at++; else while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at < text.length() && text.charAt(at) == '.') {
                at++; int digits = at; while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
                if (at == digits) throw error("无效数字");
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++; if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int digits = at; while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
                if (at == digits) throw error("无效指数");
            }
            String number = text.substring(start, at);
            try { if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.valueOf(number); return Long.valueOf(number); }
            catch (NumberFormatException e) { throw error("数字越界"); }
        }
        String string() {
            at++; StringBuilder b = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++); if (c == '"') return b.toString();
                if (c < 32) throw error("未转义的控制字符");
                if (c != '\\') { b.append(c); continue; }
                if (at >= text.length()) throw error("无效转义");
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error("无效 Unicode");
                        try { b.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                        catch (NumberFormatException ex) { throw error("无效 Unicode"); } at += 4;
                    }
                    default -> throw error("未知转义");
                }
            }
            throw error("字符串未结束");
        }
    }
}

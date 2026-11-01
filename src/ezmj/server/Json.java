package ezmj.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 連線訊息用的最小 JSON。物件是 Map，陣列是 List，整數是 Long，其他數字是 Double。
 * 內容來自網路，格式不對一律丟出 IllegalArgumentException。
 */
final class Json {

    /** 巢狀太深就拒絕，避免惡意訊息把堆疊用完。 */
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value(0);
        parser.skipSpace();
        if (parser.position != text.length()) {
            throw parser.error("多餘的內容");
        }
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            writeNumber((Number) value, out);
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, out);
        } else if (value instanceof Iterable) {
            writeArray((Iterable<?>) value, out);
        } else {
            throw new IllegalArgumentException("不能寫成 JSON: " + value.getClass());
        }
    }

    private static void writeNumber(Number number, StringBuilder out) {
        double asDouble = number.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            throw new IllegalArgumentException("JSON 沒有這個數字: " + number);
        }
        out.append(number);
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString((String) entry.getKey(), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> items, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(item, out);
        }
        out.append(']');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("巢狀太深");
        }
        skipSpace();
        char c = peek();
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:  return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipSpace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("物件的鍵要是字串");
            }
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value(depth + 1));
            skipSpace();
            char c = peek();
            position++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("物件裡要有逗號或結尾");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        position++;
        skipSpace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            list.add(value(depth + 1));
            skipSpace();
            char c = peek();
            position++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("陣列裡要有逗號或結尾");
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        position++;
        while (true) {
            char c = peek();
            position++;
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("字串裡有控制字元");
            }
            out.append(c == '\\' ? escape() : c);
        }
    }

    private char escape() {
        char c = peek();
        position++;
        switch (c) {
            case '"':
            case '\\':
            case '/': return c;
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'u': return unicode();
            default:  throw error("不認得的跳脫字元");
        }
    }

    private char unicode() {
        if (position + 4 > text.length()) {
            throw error("\\u 後面要有四位");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(position + i), 16);
            if (digit < 0) {
                throw error("\\u 後面要是十六進位");
            }
            code = code * 16 + digit;
        }
        position += 4;
        return (char) code;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("不認得的內容");
        }
        position += word.length();
        return value;
    }

    private Object number() {
        int start = position;
        while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String word = text.substring(start, position);
        if (word.isEmpty() || !word.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw error("不是數字");
        }
        try {
            return Long.valueOf(word);
        } catch (NumberFormatException notLong) {
            return Double.valueOf(word);
        }
    }

    private void skipSpace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("內容提早結束");
        }
        return text.charAt(position);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("這裡要是 " + c);
        }
        position++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON 位置 " + position + "：" + message);
    }
}

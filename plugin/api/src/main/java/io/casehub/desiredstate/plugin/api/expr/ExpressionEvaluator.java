package io.casehub.desiredstate.plugin.api.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExpressionEvaluator {

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]+)}");

    private ExpressionEvaluator() {}

    public static boolean evaluate(String expression, Map<String, Object> bindings) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionParseException("Expression is empty");
        }
        String resolved = resolveInterpolations(expression, bindings);
        List<Token> tokens = tokenize(resolved);
        Parser parser = new Parser(tokens);
        boolean result = parser.parseOr();
        if (parser.hasMore()) {
            throw new ExpressionParseException(
                "Unexpected token after expression: " + parser.peek());
        }
        return result;
    }

    private static String resolveInterpolations(String expr, Map<String, Object> bindings) {
        Matcher m = INTERPOLATION.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            Object value = bindings.get(ref);
            String replacement;
            if (value == null) {
                replacement = "null";
            } else if (value instanceof String s) {
                replacement = "\"" + s.replace("\"", "\\\"") + "\"";
            } else {
                replacement = value.toString();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int end = findClosingQuote(input, i);
                tokens.add(new Token(TokenType.STRING, input.substring(i + 1, end)));
                i = end + 1;
            } else if (c == '[') {
                tokens.add(new Token(TokenType.LBRACKET, "["));
                i++;
            } else if (c == ']') {
                tokens.add(new Token(TokenType.RBRACKET, "]"));
                i++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                i++;
            } else if (c == '!' && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.OP, "!="));
                i += 2;
            } else if (c == '=' && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.OP, "=="));
                i += 2;
            } else if (c == '<' && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.OP, "<="));
                i += 2;
            } else if (c == '>' && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                tokens.add(new Token(TokenType.OP, ">="));
                i += 2;
            } else if (c == '<') {
                tokens.add(new Token(TokenType.OP, "<"));
                i++;
            } else if (c == '>') {
                tokens.add(new Token(TokenType.OP, ">"));
                i++;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < input.length() && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, input.substring(start, i)));
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < input.length() && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                String word = input.substring(start, i);
                switch (word) {
                    case "and" -> tokens.add(new Token(TokenType.AND, "and"));
                    case "or" -> tokens.add(new Token(TokenType.OR, "or"));
                    case "not" -> tokens.add(new Token(TokenType.NOT, "not"));
                    case "in" -> tokens.add(new Token(TokenType.OP, "in"));
                    case "contains" -> tokens.add(new Token(TokenType.OP, "contains"));
                    case "null" -> tokens.add(new Token(TokenType.NULL, "null"));
                    case "true" -> tokens.add(new Token(TokenType.BOOLEAN, "true"));
                    case "false" -> tokens.add(new Token(TokenType.BOOLEAN, "false"));
                    default -> tokens.add(new Token(TokenType.STRING, word));
                }
            } else {
                throw new ExpressionParseException("Unexpected character: '" + c + "'");
            }
        }
        return tokens;
    }

    private static int findClosingQuote(String input, int openPos) {
        for (int i = openPos + 1; i < input.length(); i++) {
            if (input.charAt(i) == '"' && input.charAt(i - 1) != '\\') {
                return i;
            }
        }
        throw new ExpressionParseException("Unclosed string literal at position " + openPos);
    }

    private enum TokenType {
        NUMBER, STRING, NULL, BOOLEAN, OP, AND, OR, NOT, LBRACKET, RBRACKET, COMMA
    }

    private record Token(TokenType type, String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    private static class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }

        boolean hasMore() {
            return pos < tokens.size();
        }

        Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        Token consume() {
            return tokens.get(pos++);
        }

        boolean parseOr() {
            boolean left = parseAnd();
            while (hasMore() && peek().type == TokenType.OR) {
                consume();
                boolean right = parseAnd();
                left = left || right;
            }
            return left;
        }

        boolean parseAnd() {
            boolean left = parseNot();
            while (hasMore() && peek().type == TokenType.AND) {
                consume();
                boolean right = parseNot();
                left = left && right;
            }
            return left;
        }

        boolean parseNot() {
            if (hasMore() && peek().type == TokenType.NOT) {
                consume();
                return !parseNot();
            }
            return parseComparison();
        }

        boolean parseComparison() {
            Object left = parseValue();
            if (!hasMore() || peek().type != TokenType.OP) {
                return toBool(left);
            }
            Token op = consume();
            return switch (op.value) {
                case "==" -> eq(left, parseValue());
                case "!=" -> !eq(left, parseValue());
                case "<" -> cmp(left, parseValue()) < 0;
                case ">" -> cmp(left, parseValue()) > 0;
                case "<=" -> cmp(left, parseValue()) <= 0;
                case ">=" -> cmp(left, parseValue()) >= 0;
                case "in" -> evalIn(left);
                case "contains" -> evalContains(left, parseValue());
                default -> throw new ExpressionParseException("Unknown operator: " + op.value);
            };
        }

        Object parseValue() {
            if (!hasMore()) {
                throw new ExpressionParseException("Expected value, got end of expression");
            }
            Token t = consume();
            return switch (t.type) {
                case NUMBER -> parseNumber(t.value);
                case STRING -> t.value;
                case NULL -> null;
                case BOOLEAN -> Boolean.parseBoolean(t.value);
                case LBRACKET -> parseList();
                default -> throw new ExpressionParseException(
                    "Expected value, got: " + t);
            };
        }

        List<Object> parseList() {
            List<Object> list = new ArrayList<>();
            if (hasMore() && peek().type == TokenType.RBRACKET) {
                consume();
                return list;
            }
            list.add(parseValue());
            while (hasMore() && peek().type == TokenType.COMMA) {
                consume();
                list.add(parseValue());
            }
            if (!hasMore() || peek().type != TokenType.RBRACKET) {
                throw new ExpressionParseException("Expected ']'");
            }
            consume();
            return list;
        }

        boolean evalIn(Object left) {
            if (!hasMore() || peek().type != TokenType.LBRACKET) {
                throw new ExpressionParseException("Expected '[' after 'in'");
            }
            consume();
            List<Object> list = parseList();
            if (left == null) return false;
            for (Object item : list) {
                if (eq(left, item)) return true;
            }
            return false;
        }

        boolean evalContains(Object left, Object right) {
            if (left == null || right == null) return false;
            return left.toString().contains(right.toString());
        }

        static boolean eq(Object a, Object b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            if (a instanceof Number na && b instanceof Number nb) {
                return na.doubleValue() == nb.doubleValue();
            }
            return a.toString().equals(b.toString());
        }

        static int cmp(Object a, Object b) {
            if (a == null || b == null) return 0;
            double da = toDouble(a);
            double db = toDouble(b);
            if (Double.isNaN(da) || Double.isNaN(db)) return 0;
            return Double.compare(da, db);
        }

        static double toDouble(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(v.toString());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        static Number parseNumber(String s) {
            if (s.contains(".")) return Double.parseDouble(s);
            long l = Long.parseLong(s);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        }

        static boolean toBool(Object v) {
            if (v == null) return false;
            if (v instanceof Boolean b) return b;
            if (v instanceof Number n) return n.doubleValue() != 0;
            return !v.toString().isEmpty();
        }
    }
}

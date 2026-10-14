package com.example.lira.desktop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FinanceVoiceParser {
    private static final String VERB = "(?:потратил(?:а)?|потрачено|расходовал(?:а)?|"
            + "купил(?:а)?|заплатил(?:а)?|оплатил(?:а)?|заработал(?:а)?|"
            + "получил(?:а)?|доход|поступил(?:и)?)";
    private static final Pattern OPERATION = Pattern.compile(
            "(?iu)\\b(" + VERB + ")\\s+(.+?)(?=\\b" + VERB + "\\s+|$)");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])([0-9][0-9\\s.,]*|полторы тысячи|"
                    + "две тысячи|три тысячи|четыре тысячи|пять тысяч|"
                    + "шесть тысяч|семь тысяч|восемь тысяч|девять тысяч|"
                    + "одна тысяча|одну тысячу|тысячу|тысяча|тысячи)"
                    + "(?:\\s*(?:₽|руб(?:ль|ля|лей)?|р\\.?))?");

    private FinanceVoiceParser() {
    }

    static List<Entry> parse(String utterance) {
        List<Entry> entries = new ArrayList<>();
        Matcher operations = OPERATION.matcher(utterance);
        while (operations.find()) {
            String verb = operations.group(1).toLowerCase(Locale.ROOT);
            String details = operations.group(2).trim();
            Matcher amountMatcher = AMOUNT.matcher(details);
            if (!amountMatcher.find()) {
                continue;
            }
            long amount = parseAmount(amountMatcher.group(1));
            if (amount <= 0) {
                continue;
            }
            String title = details.substring(0, amountMatcher.start())
                    .replaceAll("[,.:;\\s]+$", "").trim();
            if (title.isBlank()) {
                title = details.substring(amountMatcher.end())
                        .replaceFirst("(?iu)^\\s*(?:руб(?:ль|ля|лей)?|р\\.?|₽)?\\s*"
                                + "(?:на|за|в)?\\s*", "")
                        .replaceAll("[,.:;\\s]+$", "").trim();
            }
            boolean income = verb.startsWith("заработ") || verb.startsWith("получ")
                    || verb.startsWith("доход") || verb.startsWith("поступ");
            String category = category(title + " " + details, income);
            entries.add(new Entry(title.isBlank() ? category : title, amount, income, category));
        }
        return entries;
    }

    private static long parseAmount(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("тысяч")) {
            String digits = normalized.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                normalized = digits + "000";
            } else if (normalized.startsWith("две")) {
                normalized = "2000";
            } else if (normalized.startsWith("три")) {
                normalized = "3000";
            } else if (normalized.startsWith("четыре")) {
                normalized = "4000";
            } else if (normalized.startsWith("пять")) {
                normalized = "5000";
            } else if (normalized.startsWith("шесть")) {
                normalized = "6000";
            } else if (normalized.startsWith("семь")) {
                normalized = "7000";
            } else if (normalized.startsWith("восемь")) {
                normalized = "8000";
            } else if (normalized.startsWith("девять")) {
                normalized = "9000";
            } else if (normalized.startsWith("полторы")) {
                normalized = "1500";
            } else {
                normalized = "1000";
            }
        } else {
            normalized = normalized.replaceAll("\\s+", "");
            if (normalized.matches("\\d{1,3}[.,]\\d{3}")) {
                normalized = normalized.replaceAll("[.,]", "");
            } else {
                normalized = normalized.replace(',', '.');
            }
        }
        try {
            return new BigDecimal(normalized).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return 0;
        }
    }

    private static String category(String title, boolean income) {
        String text = title.toLowerCase(Locale.ROOT);
        if (income) {
            return text.contains("зарплат") ? "Зарплата"
                    : text.contains("инвест") ? "Инвестиции" : "Доход";
        }
        if (text.matches(".*(кафе|ресторан|обед|ужин|завтрак|кофе|еда|йог).*")) {
            return "Кафе и еда";
        }
        if (text.matches(".*(транспорт|такси|автобус|метро|бензин|топлив).*")) {
            return "Транспорт";
        }
        if (text.matches(".*(квартир|аренд|дом|коммун).*")) {
            return "Дом";
        }
        if (text.matches(".*(продукт|магазин|покуп).*")) {
            return "Покупки";
        }
        return title.isBlank() ? "Прочее"
                : title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
    }

    record Entry(String title, long amountCents, boolean income, String category) {
    }
}

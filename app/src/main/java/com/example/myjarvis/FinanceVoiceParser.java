package com.example.myjarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinanceVoiceParser {

    private static final String VERB = "(?:потратил(?:а)?|потрачено|расход(?:овал(?:а)?)?"
            + "|купил(?:а)?|приобр[её]л(?:а)?|заплатил(?:а)?|оплатил(?:а)?"
            + "|заработал(?:а)?|получил(?:а)?|доход(?:ы)?|поступил(?:и)?)";
    private static final Pattern OPERATIONS = Pattern.compile(
            "(?iu)\\b(" + VERB + ")\\s+(.+?)(?=\\b" + VERB + "\\s+|$)");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?iu)(?<![\\p{L}\\d])([0-9][0-9\\s.,]*"
                    + "|(?:две|три|четыре|пять|шесть|семь|восемь|девять)\\s+тысяч(?:а|и)?"
                    + "|одна тысяча|одну тысячу|один тысяча|тысячу|тысячи|тысяча"
                    + "|полторы тысячи|\\d+ тысяч(?:а|и)?)(?:\\s*(?:₽|руб(?:ль|ля|лей)?|р\\.?))?");

    private FinanceVoiceParser() {
    }

    public static List<Entry> parse(String utterance) {
        List<Entry> entries = new ArrayList<>();
        Matcher operation = OPERATIONS.matcher(utterance);
        while (operation.find()) {
            String verb = operation.group(1).toLowerCase(Locale.ROOT);
            String details = operation.group(2).trim();
            Matcher amountMatcher = AMOUNT.matcher(details);
            String amountText = null;
            int amountStart = -1;
            while (amountMatcher.find()) {
                amountText = amountMatcher.group(1);
                amountStart = amountMatcher.start(1);
            }
            if (amountText == null) {
                continue;
            }

            long cents = parseAmountCents(amountText);
            if (cents <= 0) {
                continue;
            }
            boolean income = verb.startsWith("заработ")
                    || verb.startsWith("получ")
                    || verb.startsWith("доход")
                    || verb.startsWith("поступ");
            String description = details.substring(0, amountStart)
                    .replaceAll("(?iu)\\b(?:руб(?:ль|ля|лей)?|р\\.?|₽)\\s*$", "")
                    .replaceAll("(?iu)\\b(?:я|там|ещ[её]|потом|руб(?:ль|ля|лей)?|р\\.?|₽)\\b", "")
                    .replaceAll("[,.:;\\s]+$", "")
                    .trim();
            String category = categoryFor(description, income);
            if (description.isEmpty()) {
                description = income ? "Доход" : category;
            }
            entries.add(new Entry(description, category, cents, income));
        }
        return entries;
    }

    private static long parseAmountCents(String rawAmount) {
        String normalized = rawAmount.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("полторы")) {
            normalized = "1500";
        } else if (normalized.matches("две тысячи|две тысяча")) {
            normalized = "2000";
        } else if (normalized.matches("три тысячи|три тысяча")) {
            normalized = "3000";
        } else if (normalized.matches("четыре тысячи|четыре тысяча")) {
            normalized = "4000";
        } else if (normalized.matches("пять тысяч[аи]?")) {
            normalized = "5000";
        } else if (normalized.matches("шесть тысяч[аи]?")) {
            normalized = "6000";
        } else if (normalized.matches("семь тысяч[аи]?")) {
            normalized = "7000";
        } else if (normalized.matches("восемь тысяч[аи]?")) {
            normalized = "8000";
        } else if (normalized.matches("девять тысяч[аи]?")) {
            normalized = "9000";
        } else if (normalized.matches("тысячу|тысячи|тысяча")) {
            normalized = "1000";
        } else if (normalized.matches("одна тысяча|одну тысячу|один тысяча")) {
            normalized = "1000";
        } else {
            boolean thousandUnit = normalized.matches("(?iu).*тысяч(?:а|и)?$");
            normalized = normalized.replaceAll("(?iu)\\s*тысяч(?:а|и)?$", "")
                    .replaceAll("\\s+", "");
            if (thousandUnit) {
                normalized = normalized.replaceAll("[.,]", "") + "000";
            } else {
                int dot = normalized.lastIndexOf('.');
                int comma = normalized.lastIndexOf(',');
                int decimalSeparator = Math.max(dot, comma);
                if (dot >= 0 && comma >= 0) {
                    char decimal = dot > comma ? '.' : ',';
                    normalized = normalized.replace(decimal == '.' ? "," : ".", "")
                            .replace(decimal, '.');
                } else if (decimalSeparator >= 0) {
                    String fraction = normalized.substring(decimalSeparator + 1);
                    if (fraction.length() == 3) {
                        normalized = normalized.replaceAll("[.,]", "");
                    } else {
                        normalized = normalized.replaceAll("[.,]", ".");
                    }
                }
            }
        }
        try {
            double amount = Double.parseDouble(normalized);
            if (!Double.isFinite(amount) || amount <= 0 || amount > Long.MAX_VALUE / 100.0) {
                return 0;
            }
            return Math.round(amount * 100);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String categoryFor(String description, boolean income) {
        if (income) {
            if (description.toLowerCase(Locale.ROOT).contains("зарплат")) {
                return "Зарплата";
            }
            return description.toLowerCase(Locale.ROOT).contains("инвест")
                    ? "Инвестиции" : "Доход";
        }
        String text = description.toLowerCase(Locale.ROOT);
        if (text.contains("зарплат")) {
            return "Зарплата";
        }
        if (text.contains("инвест")) {
            return "Инвестиции";
        }
        if (text.matches(".*(кафе|ресторан|обед|ужин|завтрак|кофе|йогурт|еда|йог).*")) {
            return "Кафе и еда";
        }
        if (text.matches(".*(транспорт|такси|автобус|метро|бензин|топлив).*")) {
            return "Транспорт";
        }
        if (text.matches(".*(квартир|аренд|дом|коммунал).*")) {
            return "Дом";
        }
        if (text.matches(".*(продукт|магазин|покуп|купил).*")) {
            return "Покупки";
        }
        return description.isEmpty() ? "Прочее" : capitalize(description);
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(new Locale("ru")) + value.substring(1);
    }

    public static final class Entry {
        public final String title;
        public final String category;
        public final long amountCents;
        public final boolean income;

        Entry(String title, String category, long amountCents, boolean income) {
            this.title = title;
            this.category = category;
            this.amountCents = amountCents;
            this.income = income;
        }
    }
}

package org.beFree.chat;

import org.beFree.transaction.TransactionType;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a chat message into a transaction. Accepted shapes:
 * "12,50 almoço", "almoço 12.50", "3€ café #food", "+1500 salário".
 * The first number is the amount; a leading "+" means income;
 * "#word" names a category; everything else is the description.
 */
public final class MessageParser {

    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\w#])(\\+)?(\\d+(?:[.,]\\d{1,2})?)\\s*(?:€|eur)?(?![\\w])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY = Pattern.compile("#(\\p{L}[\\p{L}\\p{N}_-]*)");

    private MessageParser() {
    }

    public record Parsed(BigDecimal amount, TransactionType type, String description, String categoryName) {
    }

    public static Optional<Parsed> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        Matcher amount = AMOUNT.matcher(text);
        if (!amount.find()) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(amount.group(2).replace(',', '.'));
        if (value.signum() <= 0) {
            return Optional.empty();
        }
        TransactionType type = amount.group(1) != null ? TransactionType.INCOME : TransactionType.EXPENSE;

        String rest = text.substring(0, amount.start()) + " " + text.substring(amount.end());

        String category = null;
        Matcher cat = CATEGORY.matcher(rest);
        if (cat.find()) {
            category = cat.group(1);
            rest = cat.replaceFirst(" ");
        }

        String description = rest.trim().replaceAll("\\s+", " ");
        return Optional.of(new Parsed(value, type, description.isEmpty() ? null : description, category));
    }
}

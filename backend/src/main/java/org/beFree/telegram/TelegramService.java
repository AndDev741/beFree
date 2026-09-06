package org.beFree.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.category.Category;
import org.beFree.telegram.TelegramApi.SendMessageRequest;
import org.beFree.telegram.TelegramApi.Update;
import org.beFree.chat.MessageParser;
import org.beFree.transaction.NewTransaction;
import org.beFree.transaction.Source;
import org.beFree.transaction.Transaction;
import org.beFree.transaction.TransactionService;
import org.beFree.transaction.TransactionType;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Handles one Telegram update: authorize the chat, parse, record, reply. */
@ApplicationScoped
public class TelegramService {

    private static final Logger LOG = Logger.getLogger(TelegramService.class);
    private static final String HELP = """
            I couldn't find an amount in that. Try:
            12,50 almoço
            3€ café #food
            +1500 salário""";

    @Inject
    TelegramConfig config;

    @Inject
    @RestClient
    TelegramApi telegram;

    @Inject
    TransactionService transactions;

    public void handle(Update update) {
        if (update.message() == null || update.message().chat() == null) {
            return;
        }
        long chatId = update.message().chat().id();
        String token = config.botToken().orElseThrow();

        if (!isAllowed(chatId)) {
            LOG.warnf("Ignoring message from unauthorized chat %d", chatId);
            reply(token, chatId, "This chat is not authorized (chat id " + chatId + ").");
            return;
        }

        String text = update.message().text();
        var parsed = MessageParser.parse(text);
        if (parsed.isEmpty()) {
            reply(token, chatId, HELP);
            return;
        }
        var p = parsed.get();

        Long categoryId = null;
        String categoryNote = "";
        if (p.categoryName() != null) {
            var category = Category.findByName(p.categoryName());
            if (category.isPresent()) {
                categoryId = category.get().id;
                categoryNote = " #" + category.get().name;
            } else {
                categoryNote = " (unknown category #" + p.categoryName() + ", left blank)";
            }
        }

        LocalDate occurredOn = Instant.ofEpochSecond(update.message().date())
                .atZone(ZoneId.of(config.zone()))
                .toLocalDate();

        Transaction t = transactions.record(new NewTransaction(
                p.amount(), p.type(), null, occurredOn, p.description(), categoryId,
                Source.TELEGRAM, String.valueOf(update.updateId()), text));

        String sign = t.type == TransactionType.INCOME ? "+" : "";
        reply(token, chatId, "✅ %s%s %s · %s%s (#%d)".formatted(
                sign, t.amount.setScale(2, RoundingMode.HALF_UP), t.currency,
                t.description != null ? t.description : "no description",
                categoryNote, t.id));
    }

    private boolean isAllowed(long chatId) {
        List<Long> allowed = config.allowedChatIds().orElse(List.of());
        return allowed.contains(chatId);
    }

    private void reply(String token, long chatId, String text) {
        try {
            telegram.sendMessage(token, new SendMessageRequest(chatId, text));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send Telegram reply to chat %d", chatId);
        }
    }
}

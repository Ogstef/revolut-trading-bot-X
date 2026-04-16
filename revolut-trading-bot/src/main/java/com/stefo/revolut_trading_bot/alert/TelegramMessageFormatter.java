package com.stefo.revolut_trading_bot.alert;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Formats trading events into HTML messages for the Telegram Bot API.
 * Pure formatting — no side effects, fully testable in isolation.
 */
@Component
public class TelegramMessageFormatter {

    // ─── Error alerts ─────────────────────────────────────────────────────────

    public String formatCircuitBreakerTripped(String reason) {
        return "<b>\uD83D\uDD34 CIRCUIT BREAKER TRIPPED</b>\n" + escapeHtml(reason);
    }

    public String formatBotStopped(String triggeredBy) {
        return "<b>\uD83D\uDED1 BOT STOPPED</b>\n"
                + "Triggered by: <code>" + escapeHtml(triggeredBy) + "</code>";
    }

    public String formatBotResumed(String triggeredBy) {
        return "<b>\u2705 BOT RESUMED</b>\n"
                + "Resumed by: <code>" + escapeHtml(triggeredBy) + "</code>";
    }

    public String formatTradingCycleError(String errorMessage) {
        String truncated = errorMessage == null ? "unknown"
                : errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        return "<b>\u26A0\uFE0F TRADING CYCLE FAILED</b>\n"
                + "<code>" + escapeHtml(truncated) + "</code>";
    }

    // ─── Trade notifications ──────────────────────────────────────────────────

    public String formatPositionOpened(Position position) {
        return "<b>\uD83D\uDCC8 POSITION OPENED</b>\n"
                + "Pair: <code>" + position.getPair() + "</code>"
                + " | Strategy: <code>" + position.getStrategyName() + "</code>"
                + " | Interval: <code>" + position.getInterval() + "</code>\n"
                + "Side: <b>" + position.getSide() + "</b>"
                + " | Entry: \u20AC" + position.getEntryPrice().toPlainString()
                + " | Qty: " + position.getQuantity().toPlainString() + "\n"
                + "TP: \u20AC" + formatPrice(position.getTakeProfit())
                + " | SL: \u20AC" + formatPrice(position.getStopLoss());
    }

    public String formatPositionClosed(Position position, Trade trade) {
        boolean win = trade.getPnl() != null && trade.getPnl().signum() > 0;
        String emoji = win ? "\uD83D\uDCB0" : "\uD83D\uDCC9";
        String label = win ? "WIN" : "LOSS";

        return "<b>" + emoji + " POSITION CLOSED \u2014 " + label + "</b>\n"
                + "Pair: <code>" + position.getPair() + "</code>"
                + " | Strategy: <code>" + position.getStrategyName() + "</code>"
                + " | Interval: <code>" + position.getInterval() + "</code>\n"
                + "Entry: \u20AC" + formatPrice(trade.getEntryPrice())
                + " \u2192 Exit: \u20AC" + formatPrice(trade.getExitPrice()) + "\n"
                + "PnL: <b>\u20AC" + formatPnl(trade.getPnl()) + "</b>"
                + " (" + formatPnl(trade.getPnlPct()) + "%)\n"
                + "Reason: <code>" + (trade.getExitReason() != null ? trade.getExitReason() : "\u2014") + "</code>";
    }

    // ─── Daily summary ────────────────────────────────────────────────────────

    public String formatDailySummary(DailySummaryData data) {
        StringBuilder sb = new StringBuilder();

        sb.append("<b>\uD83D\uDCCA DAILY SUMMARY \u2014 ").append(data.date()).append("</b>\n\n");
        sb.append("Mode: <b>").append(data.botMode()).append("</b>\n");
        sb.append("Trades closed today: <b>").append(data.totalTradesClosed()).append("</b>");
        if (data.totalTradesClosed() > 0) {
            sb.append(" (").append(data.wins()).append("W / ").append(data.losses()).append("L)");
        }
        sb.append("\n");
        if (data.totalTradesClosed() > 0) {
            sb.append("Win rate: <b>").append(data.winRate().toPlainString()).append("%</b>\n");
        }

        sb.append("\n<b>\uD83D\uDCB0 P&amp;L</b>\n");
        sb.append("  Today:     \u20AC").append(data.dailyPnl().toPlainString()).append("\n");
        sb.append("  This week: \u20AC").append(data.weeklyPnl().toPlainString()).append("\n");
        sb.append("  All time:  \u20AC").append(data.allTimePnl().toPlainString()).append("\n");

        if (!data.topWinners().isEmpty()) {
            sb.append("\n<b>\uD83C\uDFC6 Top Winners</b>\n");
            appendMovers(sb, data.topWinners());
        }

        if (!data.topLosers().isEmpty()) {
            sb.append("\n<b>\uD83D\uDCC9 Top Losers</b>\n");
            appendMovers(sb, data.topLosers());
        }

        sb.append("\n\uD83D\uDCCC Open positions: ").append(data.openPositions());
        sb.append("\n\u26A1 Circuit breakers active: ").append(data.circuitBreakersActive());

        return sb.toString();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void appendMovers(StringBuilder sb, List<DailySummaryData.TopMover> movers) {
        for (int i = 0; i < movers.size(); i++) {
            DailySummaryData.TopMover m = movers.get(i);
            sb.append("  ").append(i + 1).append(". ")
                    .append(m.pair()).append(" / ").append(m.strategy()).append(" / ").append(m.interval())
                    .append(" \u2192 \u20AC").append(m.pnl().toPlainString())
                    .append("\n");
        }
    }

    private String formatPrice(BigDecimal price) {
        return price != null ? price.toPlainString() : "\u2014";
    }

    private String formatPnl(BigDecimal pnl) {
        return pnl != null ? pnl.toPlainString() : "0";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

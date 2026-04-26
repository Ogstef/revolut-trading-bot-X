package com.stefo.revolut_trading_bot.alert;

import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Formats trading events into HTML messages for the Telegram Bot API.
 * Pure formatting — no side effects, fully testable in isolation.
 */
@Component
public class TelegramMessageFormatter {

    // ─── Error alerts ─────────────────────────────────────────────────────────

    public String formatCircuitBreakerTripped(String reason) {
        return "<b>🔴 CIRCUIT BREAKER TRIPPED</b>\n" + escapeHtml(reason);
    }

    public String formatBotStopped(String triggeredBy) {
        return "<b>🛑 BOT STOPPED</b>\n"
                + "Triggered by: <code>" + escapeHtml(triggeredBy) + "</code>";
    }

    public String formatBotResumed(String triggeredBy) {
        return "<b>✅ BOT RESUMED</b>\n"
                + "Resumed by: <code>" + escapeHtml(triggeredBy) + "</code>";
    }

    public String formatTradingCycleError(String errorMessage) {
        String truncated = errorMessage == null ? "unknown"
                : errorMessage.substring(0, Math.min(errorMessage.length(), 500));
        return "<b>⚠️ TRADING CYCLE FAILED</b>\n"
                + "<code>" + escapeHtml(truncated) + "</code>";
    }

    // ─── Trade notifications ──────────────────────────────────────────────────

    public String formatPositionOpened(Position position) {
        return "<b>📈 POSITION OPENED</b>\n"
                + "Pair: <code>" + position.getPair() + "</code>"
                + " | Strategy: <code>" + position.getStrategyName() + "</code>"
                + " | Interval: <code>" + position.getInterval() + "</code>\n"
                + "Side: <b>" + position.getSide() + "</b>"
                + " | Entry: €" + formatPrice(position.getEntryPrice())
                + " | Qty: " + position.getQuantity().toPlainString() + "\n"
                + "TP: €" + formatPrice(position.getTakeProfit())
                + " | SL: €" + formatPrice(position.getStopLoss());
    }

    public String formatPositionClosed(Position position, Trade trade) {
        boolean win = trade.getNetPnl() != null
                ? trade.getNetPnl().signum() > 0
                : trade.getPnl() != null && trade.getPnl().signum() > 0;
        String emoji = win ? "💰" : "📉";
        String label = win ? "WIN" : "LOSS";

        BigDecimal entryFee  = safe(trade.getEntryFee());
        BigDecimal exitFee   = safe(trade.getExitFee());
        BigDecimal entrySlip = safe(trade.getEntrySlippage());
        BigDecimal exitSlip  = safe(trade.getExitSlippage());
        BigDecimal totalCost = entryFee.add(exitFee).add(entrySlip).add(exitSlip);
        BigDecimal grossPnl  = safe(trade.getPnl());
        BigDecimal netPnl    = safe(trade.getNetPnl());

        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(emoji).append(" POSITION CLOSED — ").append(label).append("</b>\n");
        sb.append("Pair: <code>").append(position.getPair()).append("</code>")
          .append(" | Strategy: <code>").append(position.getStrategyName()).append("</code>")
          .append(" | Interval: <code>").append(position.getInterval()).append("</code>\n");
        sb.append("Entry: €").append(formatPrice(trade.getEntryPrice()))
          .append(" → Exit: €").append(formatPrice(trade.getExitPrice())).append("\n");

        // Gross PnL
        sb.append("Gross P&amp;L: <b>").append(signedEur(grossPnl));
        if (trade.getPnlPct() != null) {
            sb.append(" (").append(signedPct(trade.getPnlPct())).append("%)");
        }
        sb.append("</b>\n");

        // Fee breakdown — only show if we have cost data
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("╰ Fees: €").append(fmt2(entryFee))
              .append(" in + €").append(fmt2(exitFee)).append(" out");
            sb.append(" | Slip: €").append(fmt2(entrySlip))
              .append(" + €").append(fmt2(exitSlip)).append("\n");
            sb.append("╰ Total cost: €").append(fmt2(totalCost));

            // Fee drag %
            if (grossPnl.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal drag = totalCost
                        .divide(grossPnl.abs(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
                sb.append(" (").append(drag.toPlainString()).append("% drag)");
            }
            sb.append("\n");

            // Net PnL
            sb.append("Net P&amp;L: <b>").append(signedEur(netPnl));
            if (trade.getNetPnlPct() != null) {
                sb.append(" (").append(signedPct(trade.getNetPnlPct())).append("%)");
            }
            sb.append("</b>\n");
        }

        sb.append("Reason: <code>")
          .append(trade.getExitReason() != null ? trade.getExitReason() : "—")
          .append("</code>");

        return sb.toString();
    }

    // ─── Daily summary ────────────────────────────────────────────────────────

    public String formatDailySummary(DailySummaryData data) {
        StringBuilder sb = new StringBuilder();

        sb.append("<b>📊 DAILY SUMMARY — ").append(data.date()).append("</b>\n\n");
        sb.append("Mode: <b>").append(data.botMode()).append("</b>\n");
        sb.append("Trades today: <b>").append(data.totalTradesClosed()).append("</b>");
        if (data.totalTradesClosed() > 0) {
            sb.append(" (").append(data.wins()).append("W / ").append(data.losses()).append("L)");
        }
        sb.append("\n");
        if (data.totalTradesClosed() > 0) {
            sb.append("Win rate: <b>").append(data.winRate().toPlainString()).append("%</b>\n");
        }

        // P&L with gross vs net
        sb.append("\n<b>💰 P&amp;L</b>\n");
        sb.append("  Today:       gross €").append(fmt2(data.dailyPnl()))
          .append(" | net €").append(fmt2(data.dailyNetPnl())).append("\n");
        sb.append("  This week:   gross €").append(fmt2(data.weeklyPnl()))
          .append(" | net €").append(fmt2(data.weeklyNetPnl())).append("\n");
        sb.append("  All time:    gross €").append(fmt2(data.allTimePnl()))
          .append(" | net €").append(fmt2(data.allTimeNetPnl())).append("\n");

        // Fee breakdown — only if trades were closed today
        if (data.totalTradesClosed() > 0) {
            BigDecimal totalCost = safe(data.dailyFees()).add(safe(data.dailySlippage()));
            sb.append("\n<b>💸 Today's Costs</b>\n");
            sb.append("  Fees:      €").append(fmt2(data.dailyFees())).append("\n");
            sb.append("  Slippage:  €").append(fmt2(data.dailySlippage())).append("\n");
            sb.append("  Total:     €").append(fmt2(totalCost));

            // Fee drag against today's gross
            BigDecimal grossAbs = safe(data.dailyPnl()).abs();
            if (grossAbs.compareTo(BigDecimal.ZERO) > 0 && totalCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drag = totalCost
                        .divide(grossAbs, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
                sb.append(" (").append(drag.toPlainString()).append("% drag)");
            }
            sb.append("\n");
        }

        if (!data.topWinners().isEmpty()) {
            sb.append("\n<b>🏆 Top Winners</b>\n");
            appendMovers(sb, data.topWinners());
        }

        if (!data.topLosers().isEmpty()) {
            sb.append("\n<b>📉 Top Losers</b>\n");
            appendMovers(sb, data.topLosers());
        }

        sb.append("\n📌 Open positions: ").append(data.openPositions());
        sb.append("\n⚡ Circuit breakers active: ").append(data.circuitBreakersActive());

        return sb.toString();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void appendMovers(StringBuilder sb, List<DailySummaryData.TopMover> movers) {
        for (int i = 0; i < movers.size(); i++) {
            DailySummaryData.TopMover m = movers.get(i);
            sb.append("  ").append(i + 1).append(". ")
              .append(m.pair()).append(" / ").append(m.strategy()).append(" / ").append(m.interval())
              .append(" → gross €").append(m.grossPnl().toPlainString())
              .append(" | net €").append(m.netPnl().toPlainString())
              .append("\n");
        }
    }

    private String fmt2(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00";
    }

    private String signedEur(BigDecimal v) {
        if (v == null) return "€0.00";
        String plain = v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return v.signum() >= 0 ? "+€" + plain : "€" + plain;
    }

    private String signedPct(BigDecimal v) {
        if (v == null) return "0.00";
        String plain = v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return v.signum() >= 0 ? "+" + plain : plain;
    }

    private String formatPrice(BigDecimal price) {
        return price != null ? price.toPlainString() : "—";
    }

    private static BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

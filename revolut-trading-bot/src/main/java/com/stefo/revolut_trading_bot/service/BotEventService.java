package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.model.entity.BotEvent;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.entity.Trade;
import com.stefo.revolut_trading_bot.model.enums.BotEventSeverity;
import com.stefo.revolut_trading_bot.model.enums.BotEventType;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.BotEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * Persists audit events in a brand-new transaction per write, so an audit failure can NEVER roll
 * back the caller's transaction (trades, config changes, etc.). All exceptions are swallowed and
 * logged at WARN — audit writes are best-effort.
 */
@Service
@Slf4j
public class BotEventService {

    private final BotEventRepository repo;
    private final TransactionTemplate newTxTemplate;

    public BotEventService(BotEventRepository repo, PlatformTransactionManager txm) {
        this.repo = repo;
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setName("botEventAuditWrite");
        this.newTxTemplate = new TransactionTemplate(txm, def);
    }

    public void recordPositionOpened(Position p, String reason) {
        String title = "Position opened — " + p.getStrategyName() + " " + p.getPair() + " " + p.getSide();
        String detail = String.format("Entry €%s · qty %s · Reason: %s",
                p.getEntryPrice(), p.getQuantity(), reason == null ? "—" : reason);
        saveSafely(BotEvent.builder()
                .type(BotEventType.POSITION_OPENED)
                .severity(BotEventSeverity.INFO)
                .pair(p.getPair())
                .interval(p.getInterval())
                .strategy(p.getStrategyName().name())
                .title(title)
                .detail(detail)
                .build());
    }

    public void recordPositionClosed(Trade t) {
        boolean win = t.getPnl() != null && t.getPnl().signum() > 0;
        String title = "Position closed — " + t.getStrategyName() + " " + t.getPair() + " " + (win ? "WIN" : "LOSS");
        String detail = String.format("Exit reason: %s · PnL €%s (%s%%)",
                t.getExitReason(),
                t.getPnl() == null ? "—" : t.getPnl().toPlainString(),
                t.getPnlPct() == null ? "—" : t.getPnlPct().toPlainString());
        saveSafely(BotEvent.builder()
                .type(BotEventType.POSITION_CLOSED)
                .severity(win ? BotEventSeverity.INFO : BotEventSeverity.WARNING)
                .pair(t.getPair())
                .interval(t.getInterval())
                .strategy(t.getStrategyName().name())
                .title(title)
                .detail(detail)
                .build());
    }

    public void recordPositionLiquidated(Trade t) {
        String title = "LIQUIDATED — " + t.getVehicle() + " " + t.getStrategyName()
                + " " + t.getPair() + " " + t.getSide();
        String detail = String.format("Leverage: %dx · Collateral lost: €%s · Exit reason: LIQUIDATED",
                (int) t.getLeverage(),
                t.getCollateral() == null ? "—" : t.getCollateral().toPlainString());
        saveSafely(BotEvent.builder()
                .type(BotEventType.POSITION_LIQUIDATED)
                .severity(BotEventSeverity.CRITICAL)
                .pair(t.getPair())
                .interval(t.getInterval())
                .strategy(t.getStrategyName().name())
                .title(title)
                .detail(detail)
                .build());
    }

    public void recordCircuitBreakerTripped(String pair, String interval, StrategyType strategy, String cause) {
        saveSafely(BotEvent.builder()
                .type(BotEventType.CIRCUIT_BREAKER_TRIPPED)
                .severity(BotEventSeverity.CRITICAL)
                .pair(pair).interval(interval).strategy(strategy.name())
                .title("Circuit breaker TRIPPED — " + strategy + " " + pair + " " + interval)
                .detail(cause)
                .build());
    }

    public void recordCircuitBreakerReset(String pair, String interval, StrategyType strategy) {
        saveSafely(BotEvent.builder()
                .type(BotEventType.CIRCUIT_BREAKER_RESET)
                .severity(BotEventSeverity.INFO)
                .pair(pair).interval(interval).strategy(strategy.name())
                .title("Circuit breaker reset — " + strategy + " " + pair + " " + interval)
                .build());
    }

    public void recordBotStopped(String actor) {
        saveSafely(BotEvent.builder()
                .type(BotEventType.BOT_STOPPED)
                .severity(BotEventSeverity.CRITICAL)
                .title("Bot stopped")
                .detail("Triggered by: " + (actor == null ? "unknown" : actor))
                .build());
    }

    public void recordBotResumed(String actor) {
        saveSafely(BotEvent.builder()
                .type(BotEventType.BOT_RESUMED)
                .severity(BotEventSeverity.INFO)
                .title("Bot resumed")
                .detail("Triggered by: " + (actor == null ? "unknown" : actor))
                .build());
    }

    public void recordConfigChanged(String patchJson) {
        saveSafely(BotEvent.builder()
                .type(BotEventType.CONFIG_CHANGED)
                .severity(BotEventSeverity.INFO)
                .title("Config updated")
                .detail(patchJson)
                .metadata(patchJson)
                .build());
    }

    public List<BotEvent> recent(int limit, Set<BotEventType> typeFilter) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        if (typeFilter == null || typeFilter.isEmpty()) {
            return repo.findAllByOrderByCreatedAtDesc(page);
        }
        return repo.findByTypeInOrderByCreatedAtDesc(typeFilter, page);
    }

    private void saveSafely(BotEvent event) {
        try {
            newTxTemplate.execute(status -> {
                repo.save(event);
                return null;
            });
        } catch (Exception e) {
            log.warn("Audit write failed for type={}: {}", event.getType(), e.getMessage());
        }
    }
}

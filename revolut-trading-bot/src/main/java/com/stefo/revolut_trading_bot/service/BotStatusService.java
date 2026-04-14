package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.BotStatusResponse;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import com.stefo.revolut_trading_bot.repository.TradeRepository;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.scheduler.BotStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BotStatusService {

    private final TradingConfig      tradingConfig;
    private final RiskManager        riskManager;
    private final BotStateService    botStateService;
    private final PositionRepository positionRepository;
    private final TradeRepository    tradeRepository;

    public BotStatusResponse getStatus() {
        // Global open-position count — every strategy, pair, interval.
        long openPositions = positionRepository.countByStatus(OrderStatus.OPEN);

        // Global daily realised PnL since midnight.
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        BigDecimal dailyPnl = tradeRepository.sumPnlSince(startOfDay);

        // Consecutive-loss counter and circuit-breaker flags follow the primary
        // (pair, interval, strategy) triple — matches API_CONTRACT.md.
        BigDecimal balance = tradingConfig.getPaperBalance();
        RiskManager.RiskStatus primaryRisk = riskManager.currentStatusForStrategy(
                balance,
                tradingConfig.primaryPair(),
                tradingConfig.primaryInterval(),
                tradingConfig.getPrimaryStrategy());

        return new BotStatusResponse(
                botStateService.isActive(),
                tradingConfig.getMode(),
                tradingConfig.primaryPair(),
                openPositions,
                dailyPnl,
                primaryRisk.consecutiveLosses(),
                primaryRisk.anyCircuitBreakerTripped(),
                Instant.now()
        );
    }
}

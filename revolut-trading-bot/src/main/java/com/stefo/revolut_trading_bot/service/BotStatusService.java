package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.BotStatusResponse;
import com.stefo.revolut_trading_bot.risk.RiskManager;
import com.stefo.revolut_trading_bot.scheduler.BotStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BotStatusService {

    private final TradingConfig tradingConfig;
    private final RiskManager riskManager;
    private final BotStateService botStateService;

    public BotStatusResponse getStatus(){
        BigDecimal balance = tradingConfig.getPaperBalance();
        RiskManager.RiskStatus risk = riskManager.currentStatus(balance);

        return buildResponse(risk);
    }

    private BotStatusResponse buildResponse(RiskManager.RiskStatus risk) {
        return new BotStatusResponse(
                botStateService.isActive(),
                tradingConfig.getMode(),
                tradingConfig.primaryPair(),
                risk.openPositions(),
                risk.dailyPnl(),
                risk.consecutiveLosses(),
                risk.anyCircuitBreakerTripped(),
                Instant.now()
        );
    }
}

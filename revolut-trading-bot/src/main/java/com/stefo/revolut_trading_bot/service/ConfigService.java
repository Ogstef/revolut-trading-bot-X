package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.config.TradingConfig;
import com.stefo.revolut_trading_bot.model.dto.ConfigUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final TradingConfig tradingConfig;
    private final BotEventService botEventService;


    public void UpdateConfigs (ConfigUpdateRequest req) {

        TradingConfig.Risk risk = tradingConfig.getRisk();
        TradingConfig.Strategy strategy = tradingConfig.getStrategy();

        if (req.maxPositionPct() != null)       risk.setMaxPositionPct(req.maxPositionPct());
        if (req.maxConcurrentPositions() != null) risk.setMaxConcurrentPositions(req.maxConcurrentPositions());
        if (req.maxDailyLossPct() != null)      risk.setMaxDailyLossPct(req.maxDailyLossPct());
        if (req.maxConsecutiveLosses() != null)  risk.setMaxConsecutiveLosses(req.maxConsecutiveLosses());
        if (req.takeProfitPct() != null)         risk.setTakeProfitPct(req.takeProfitPct());
        if (req.stopLossPct() != null)           risk.setStopLossPct(req.stopLossPct());

        if (req.emaShortPeriod() != null)  strategy.setEmaShortPeriod(req.emaShortPeriod());
        if (req.emaLongPeriod() != null)   strategy.setEmaLongPeriod(req.emaLongPeriod());
        if (req.rsiPeriod() != null)       strategy.setRsiPeriod(req.rsiPeriod());
        if (req.rsiOverbought() != null)   strategy.setRsiOverbought(req.rsiOverbought());
        if (req.rsiOversold() != null)     strategy.setRsiOversold(req.rsiOversold());

        if (req.paperBalance() != null)    tradingConfig.setPaperBalance(req.paperBalance());

        botEventService.recordConfigChanged(req.toString());
    }
}

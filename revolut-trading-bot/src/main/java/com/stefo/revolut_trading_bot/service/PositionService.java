package com.stefo.revolut_trading_bot.service;

import com.stefo.revolut_trading_bot.market.MarketDataService;
import com.stefo.revolut_trading_bot.model.dto.PositionView;
import com.stefo.revolut_trading_bot.model.entity.Position;
import com.stefo.revolut_trading_bot.model.enums.OrderStatus;
import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import com.stefo.revolut_trading_bot.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static com.stefo.revolut_trading_bot.utils.PositionUtils.toPositionView;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PositionService {

    private final PositionRepository repository;
    private final MarketDataService marketDataService;

    public List<PositionView> getPositions(String pair) {
        List<Position> positions = getPairs(pair);
        return getViews(positions);

    }
    public List<Position> getOpenPositions(String effectivePair, StrategyType strategyType) {
        return repository.findByStatusAndPairAndStrategyName(OrderStatus.OPEN, effectivePair, strategyType);
    }

    public List<Position> getOpenPositions(String effectivePair, String interval, StrategyType strategyType) {
        return repository.findByStatusAndPairAndIntervalAndStrategyName(OrderStatus.OPEN, effectivePair, interval, strategyType);
    }

    private List<PositionView> getViews(List<Position> positions) {
        return positions.stream()
                .map(p -> {
                    BigDecimal price = marketDataService.getCurrentPriceForPair(p.getPair());
                            return toPositionView(p,price);})
                .toList();
    }



    public List<Position> getPairs(String pair) {
        return pair != null ? repository.findByPairAndStatus(pair, OrderStatus.OPEN)
                : repository.findByStatus(OrderStatus.OPEN);

    }

}

package com.stefo.revolut_trading_bot.backtest;

import com.stefo.revolut_trading_bot.model.enums.StrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public ResponseEntity<BacktestRunDetail> run(@RequestBody BacktestRequest req) {
        try {
            return ResponseEntity.ok(backtestService.run(req));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/walk-forward")
    public ResponseEntity<WalkForwardResult> walkForward(@RequestBody WalkForwardRequest body) {
        try {
            int windows = body.windows() == null ? 3 : body.windows();
            return ResponseEntity.ok(backtestService.runWalkForward(body.request(), windows));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/runs")
    public ResponseEntity<List<BacktestRunSummary>> listRuns(
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) StrategyType strategy,
            @RequestParam(required = false) String interval,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(backtestService.listRecent(pair, strategy, interval, limit));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<BacktestRunDetail> getRun(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(backtestService.get(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/runs/{id}")
    public ResponseEntity<Void> deleteRun(@PathVariable UUID id) {
        backtestService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/runs/{id}")
    public ResponseEntity<BacktestRunSummary> patchRun(@PathVariable UUID id,
                                                       @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(backtestService.patchMetadata(id,
                    body.get("label"), body.get("notes")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record WalkForwardRequest(BacktestRequest request, Integer windows) {}
}

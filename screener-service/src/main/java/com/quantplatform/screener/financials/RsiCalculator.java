package com.quantplatform.screener.financials;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Relative Strength Index, version {@value #VERSION}.
 *
 * <p>Informational technical indicator; it is not a model input and never changes a score.
 * Inputs are split/dividend adjusted closes, ascending by session. The calculation uses the
 * contiguous run of sessions with a close that ends at the score date (a missing session
 * ends the run). The first {@value #PERIOD} changes seed the average gain/loss with a simple
 * mean; every later change uses Wilder smoothing: {@code avg = (avg * 13 + x) / 14}.
 * RSI = 100 - 100 / (1 + avgGain / avgLoss); 100 when there is no loss in the window.
 */
public final class RsiCalculator {
    public static final int PERIOD = 14;
    public static final String VERSION = "RSI_14_WILDER_V1";
    public static final String METHOD = "WILDER_SMOOTHING_SIMPLE_MEAN_SEED";
    public static final String MISSING_SCORE_DATE_PRICE = "MISSING_SCORE_DATE_PRICE";
    public static final String INSUFFICIENT_PRICE_HISTORY = "INSUFFICIENT_PRICE_HISTORY";
    public static final String INVALID_PRICE = "INVALID_PRICE";
    public static final String FLAT_PRICE_WINDOW = "FLAT_PRICE_WINDOW";

    /** One exchange session; {@code close} is null when the session has no usable bar. */
    public record SessionClose(LocalDate session, BigDecimal close) {}

    public record Result(BigDecimal value, String reason, int sessionsUsed,
            LocalDate firstSession, LocalDate lastSession) {}

    private RsiCalculator() {}

    public static Result compute(List<SessionClose> ascending, LocalDate scoreDate) {
        if (ascending.isEmpty()) {
            return new Result(null, MISSING_SCORE_DATE_PRICE, 0, null, null);
        }
        SessionClose last = ascending.getLast();
        if (!last.session().equals(scoreDate) || last.close() == null) {
            return new Result(null, MISSING_SCORE_DATE_PRICE, 0, null, null);
        }
        var window = new ArrayList<SessionClose>();
        for (int i = ascending.size() - 1; i >= 0 && ascending.get(i).close() != null; i--) {
            window.addFirst(ascending.get(i));
        }
        LocalDate first = window.getFirst().session();
        int used = window.size();
        if (window.stream().anyMatch(s -> s.close().signum() <= 0)) {
            return new Result(null, INVALID_PRICE, used, first, scoreDate);
        }
        if (used < PERIOD + 1) {
            return new Result(null, INSUFFICIENT_PRICE_HISTORY, used, first, scoreDate);
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= PERIOD; i++) {
            double change = change(window, i);
            gain += Math.max(change, 0);
            loss += Math.max(-change, 0);
        }
        gain /= PERIOD;
        loss /= PERIOD;
        for (int i = PERIOD + 1; i < used; i++) {
            double change = change(window, i);
            gain = (gain * (PERIOD - 1) + Math.max(change, 0)) / PERIOD;
            loss = (loss * (PERIOD - 1) + Math.max(-change, 0)) / PERIOD;
        }
        if (loss == 0 && gain == 0) {
            return new Result(null, FLAT_PRICE_WINDOW, used, first, scoreDate);
        }
        double rsi = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        return new Result(BigDecimal.valueOf(rsi).setScale(4, RoundingMode.HALF_EVEN), null,
                used, first, scoreDate);
    }

    private static double change(List<SessionClose> window, int index) {
        return window.get(index).close().doubleValue() - window.get(index - 1).close().doubleValue();
    }
}

package com.quantplatform.scoring.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

public class TemporalSymbolId implements Serializable {

    private Instant time;
    private String symbol;

    public TemporalSymbolId() {
    }

    public TemporalSymbolId(Instant time, String symbol) {
        this.time = time;
        this.symbol = symbol;
    }

    public Instant getTime() {
        return time;
    }

    public String getSymbol() {
        return symbol;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof TemporalSymbolId other)) {
            return false;
        }
        return Objects.equals(time, other.time) && Objects.equals(symbol, other.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, symbol);
    }
}

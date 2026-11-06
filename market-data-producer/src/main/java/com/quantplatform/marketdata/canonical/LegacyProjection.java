package com.quantplatform.marketdata.canonical;

import com.quantplatform.marketdata.event.FundamentalSnapshot;
import com.quantplatform.marketdata.event.StockBar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Legacy read model {@code public.market_bars} / {@code public.fundamental_snapshots}, still read by
 * the old research CLI. The upsert keeps the replace-on-same-key behaviour of the former JPA save.
 * Runs inside the canonical transaction.
 */
public class LegacyProjection {
    private final JdbcClient jdbc;

    public LegacyProjection(DataSource source) { jdbc = JdbcClient.create(source); }

    public void bar(String symbol, String provider, StockBar bar) {
        jdbc.sql("""
                INSERT INTO public.market_bars (time,symbol,provider,open_price,high_price,low_price,close_price,
                    volume,vwap,trade_count)
                VALUES (:time,:symbol,:provider,:open,:high,:low,:close,:volume,:vwap,:trades)
                ON CONFLICT (time,symbol) DO UPDATE SET provider=EXCLUDED.provider,open_price=EXCLUDED.open_price,
                    high_price=EXCLUDED.high_price,low_price=EXCLUDED.low_price,close_price=EXCLUDED.close_price,
                    volume=EXCLUDED.volume,vwap=EXCLUDED.vwap,trade_count=EXCLUDED.trade_count
                """).param("time", bar.time().atOffset(ZoneOffset.UTC)).param("symbol", normalize(symbol))
                .param("provider", provider).param("open", bar.open()).param("high", bar.high()).param("low", bar.low())
                .param("close", bar.close()).param("volume", bar.volume()).param("vwap", bar.volumeWeightedAveragePrice())
                .param("trades", bar.tradeCount()).update();
    }

    public void fundamentals(String symbol, String provider, Instant observedAt, FundamentalSnapshot f) {
        jdbc.sql("""
                INSERT INTO public.fundamental_snapshots (time,symbol,provider,company_name,asset_type,exchange,currency,
                    country,sector,industry,latest_quarter,market_capitalization,revenue_ttm,ebitda,pe_ratio,peg_ratio,
                    price_to_book_ratio,earnings_per_share,profit_margin,operating_margin_ttm,return_on_assets_ttm,
                    return_on_equity_ttm,quarterly_revenue_growth_yoy,quarterly_earnings_growth_yoy,analyst_target_price,beta)
                VALUES (:time,:symbol,:provider,:name,:assetType,:exchange,:currency,:country,:sector,:industry,:quarter,
                    :marketCap,:revenue,:ebitda,:pe,:peg,:pb,:eps,:profitMargin,:operatingMargin,:roa,:roe,:revenueGrowth,
                    :earningsGrowth,:target,:beta)
                ON CONFLICT (time,symbol) DO UPDATE SET provider=EXCLUDED.provider,company_name=EXCLUDED.company_name,
                    asset_type=EXCLUDED.asset_type,exchange=EXCLUDED.exchange,currency=EXCLUDED.currency,
                    country=EXCLUDED.country,sector=EXCLUDED.sector,industry=EXCLUDED.industry,
                    latest_quarter=EXCLUDED.latest_quarter,market_capitalization=EXCLUDED.market_capitalization,
                    revenue_ttm=EXCLUDED.revenue_ttm,ebitda=EXCLUDED.ebitda,pe_ratio=EXCLUDED.pe_ratio,
                    peg_ratio=EXCLUDED.peg_ratio,price_to_book_ratio=EXCLUDED.price_to_book_ratio,
                    earnings_per_share=EXCLUDED.earnings_per_share,profit_margin=EXCLUDED.profit_margin,
                    operating_margin_ttm=EXCLUDED.operating_margin_ttm,return_on_assets_ttm=EXCLUDED.return_on_assets_ttm,
                    return_on_equity_ttm=EXCLUDED.return_on_equity_ttm,
                    quarterly_revenue_growth_yoy=EXCLUDED.quarterly_revenue_growth_yoy,
                    quarterly_earnings_growth_yoy=EXCLUDED.quarterly_earnings_growth_yoy,
                    analyst_target_price=EXCLUDED.analyst_target_price,beta=EXCLUDED.beta
                """).param("time", observedAt.atOffset(ZoneOffset.UTC)).param("symbol", normalize(symbol))
                .param("provider", provider).param("name", f.name()).param("assetType", f.assetType())
                .param("exchange", f.exchange()).param("currency", f.currency()).param("country", f.country())
                .param("sector", f.sector()).param("industry", f.industry()).param("quarter", f.latestQuarter())
                .param("marketCap", f.marketCapitalization()).param("revenue", f.revenueTtm()).param("ebitda", f.ebitda())
                .param("pe", f.peRatio()).param("peg", f.pegRatio()).param("pb", f.priceToBookRatio())
                .param("eps", f.earningsPerShare()).param("profitMargin", f.profitMargin())
                .param("operatingMargin", f.operatingMarginTtm()).param("roa", f.returnOnAssetsTtm())
                .param("roe", f.returnOnEquityTtm()).param("revenueGrowth", f.quarterlyRevenueGrowthYoy())
                .param("earningsGrowth", f.quarterlyEarningsGrowthYoy()).param("target", f.analystTargetPrice())
                .param("beta", f.beta()).update();
    }

    /** Same validation as the former MarketDataEvent: upper case, at most 10 ticker characters. */
    private static String normalize(String symbol) {
        String value = symbol.toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9./-]{1,10}")) throw new IllegalArgumentException("symbol is invalid");
        return value;
    }
}

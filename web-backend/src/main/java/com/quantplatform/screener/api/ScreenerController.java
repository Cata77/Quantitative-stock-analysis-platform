package com.quantplatform.screener.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import com.quantplatform.screener.ranking.RankingFilter;
import com.quantplatform.screener.ranking.RankingFilter.*;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantplatform.screener.ranking.RankingPage;
import com.quantplatform.screener.ranking.RankingService;
import com.quantplatform.screener.search.CompanySearchPage;
import com.quantplatform.screener.search.CompanySearchService;

@Validated
@RestController
@RequestMapping("/screener")
public class ScreenerController {

    private final RankingService rankingService;
    private final CompanySearchService searchService;

    public ScreenerController(
            RankingService rankingService,
            CompanySearchService searchService
    ) {
        this.rankingService = rankingService;
        this.searchService = searchService;
    }

    @GetMapping("/rankings")
    public RankingPage rankings(
            @RequestParam(required = false)
            String asOf,
            @RequestParam(defaultValue="UNION") Universe universe,
            @RequestParam(required=false) UUID modelVersion,
            @RequestParam(required=false) UUID runId,
            @RequestParam(required=false) UUID instrumentId,
            @RequestParam(required=false) String profile,
            @RequestParam(required=false) String sector,
            @RequestParam(required=false) String peerGroup,
            @RequestParam(defaultValue="ELIGIBLE") Eligibility eligibility,
            @RequestParam(required=false) String warning,
            @RequestParam(defaultValue="COMPOSITE") Sort sort,
            @RequestParam(defaultValue="DESC") Direction direction,
            @RequestParam(required=false) String metric,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must not be negative")
            @Max(value = 100_000, message = "page is too large")
            int page,
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 200, message = "size must not exceed 200")
            int size
    ) {
        LocalDate date=null;
        if(asOf!=null) {
            try { date=asOf.length()==10?LocalDate.parse(asOf):Instant.parse(asOf).atOffset(ZoneOffset.UTC).toLocalDate(); }
            catch(java.time.DateTimeException e) { throw new IllegalArgumentException("asOf must be an ISO date or timestamp"); }
        }
        return rankingService.findRankings(date,page,size,new RankingFilter(universe,modelVersion,
                profile,sector,peerGroup,eligibility,warning,sort,direction,metric,runId,instrumentId));
    }

    @GetMapping("/search")
    public CompanySearchPage search(
            @RequestParam(name = "q")
            @NotBlank(message = "q must not be blank")
            @Size(max = 100, message = "q must not exceed 100 characters")
            String query,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must not be negative")
            @Max(value = 100_000, message = "page is too large")
            int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100")
            int size
    ) {
        return searchService.search(query, page, size);
    }
}

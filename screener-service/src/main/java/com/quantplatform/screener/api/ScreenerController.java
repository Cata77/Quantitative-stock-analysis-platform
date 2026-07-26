package com.quantplatform.screener.api;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
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
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must not be negative")
            @Max(value = 100_000, message = "page is too large")
            int page,
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 200, message = "size must not exceed 200")
            int size
    ) {
        return rankingService.findRankings(asOf, page, size);
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

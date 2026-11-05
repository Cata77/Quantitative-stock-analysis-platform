package com.quantplatform.screener.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantplatform.screener.financials.FinancialsService;

@RestController
@RequestMapping("/screener/instruments")
public class FinancialsController {
    private final FinancialsService service;

    public FinancialsController(FinancialsService service) {
        this.service = service;
    }

    public enum View { SUMMARY, FULL }

    /**
     * runId is required so the data always belongs to the publication the client consulted.
     * view=SUMMARY (default) is the compact user view; view=FULL is the audit view with provenance.
     */
    @GetMapping("/{instrumentId}/financials")
    public Object financials(@PathVariable UUID instrumentId, @RequestParam UUID runId,
            @RequestParam(defaultValue = "SUMMARY") View view) {
        return view == View.FULL ? service.find(runId, instrumentId) : service.summary(runId, instrumentId);
    }
}

package com.quantplatform.screener.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantplatform.screener.sectors.SectorComparison;
import com.quantplatform.screener.sectors.SectorComparisonRepository.SectorCount;
import com.quantplatform.screener.sectors.SectorComparisonService;

/** runId is required: a sector comparison always belongs to the publication the client consulted. */
@Validated
@RestController
@RequestMapping("/screener/sectors")
public class SectorController {
    private final SectorComparisonService service;

    public SectorController(SectorComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public List<SectorCount> sectors(@RequestParam UUID runId) {
        return service.sectors(runId);
    }

    @GetMapping("/{sector}/comparison")
    public SectorComparison comparison(
            @PathVariable @NotBlank @Size(max = 100, message = "sector must not exceed 100 characters") String sector,
            @RequestParam UUID runId) {
        return service.compare(runId, sector);
    }
}

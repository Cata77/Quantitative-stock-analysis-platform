package com.quantplatform.marketdata.operations;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestionStatusController {
    private final IngestionCoordinator coordinator;
    public IngestionStatusController(IngestionCoordinator coordinator) { this.coordinator = coordinator; }
    @GetMapping("/internal/ingestion/status")
    public IngestionCoordinator.Status status() { return coordinator.status(); }
}

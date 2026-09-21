package com.quantplatform.screener.api;
import java.util.Map;
import com.quantplatform.screener.search.SearchRebuildService;
import org.springframework.web.bind.annotation.*;
@RestController
public class SearchStatusController {
    private final SearchRebuildService service;
    public SearchStatusController(SearchRebuildService service) { this.service=service; }
    @GetMapping("/screener/search/status")
    public Map<String,Object> status() { return service.status(); }
}

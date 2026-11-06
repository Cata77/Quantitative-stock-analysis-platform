package com.quantplatform.screener.api;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
/** Search reads PostgreSQL directly since P2s; there is no index to build or to fall behind. */
@RestController
public class SearchStatusController {
    @GetMapping("/screener/search/status")
    public Map<String,Object> status() { return Map.of("status","LIVE","backend","POSTGRESQL","schema_version",1); }
}

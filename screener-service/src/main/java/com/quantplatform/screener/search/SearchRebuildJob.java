package com.quantplatform.screener.search;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SearchRebuildJob implements ApplicationRunner {
    private final SearchRebuildService service;
    private final ConfigurableApplicationContext context;
    private final boolean enabled;
    private final String mode;
    public SearchRebuildJob(SearchRebuildService service,ConfigurableApplicationContext context,
            @Value("${screener.search-rebuild.enabled:true}") boolean enabled,
            @Value("${screener.mode:serve}") String mode) {
        this.service=service;this.context=context;this.enabled=enabled;this.mode=mode;
        if(!mode.equals("serve")&&!mode.equals("rebuild-and-exit")) throw new IllegalArgumentException("Unknown screener mode");
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(mode.equals("rebuild-and-exit")) {
            String result=service.rebuild(true);
            if(result.equals("BUSY")) throw new IllegalStateException("Another search rebuild is running");
            LoggerFactory.getLogger(getClass()).info("Search rebuilt: {}",result);
            context.close();
        } else reconcile();
    }
    @Scheduled(fixedDelayString="${screener.search-rebuild.interval:300000}",initialDelayString="${screener.search-rebuild.interval:300000}")
    public void reconcile() {
        if(!enabled || !mode.equals("serve")) return;
        try { service.rebuild(false); }
        catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Search reconciliation failed; will retry. SQL rankings remain available",e); }
    }
}

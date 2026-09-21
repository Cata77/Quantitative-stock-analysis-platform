
package com.quantplatform.screener.search;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
class SearchRebuildJobTest {
    @Test void commandRebuildsAndClosesWhileFailureRemainsAnError() throws Exception {
        var service=mock(SearchRebuildService.class);var context=mock(ConfigurableApplicationContext.class);
        var job=new SearchRebuildJob(service,context,false,"rebuild-and-exit");
        when(service.rebuild(true)).thenReturn("generation");job.run(null);verify(context).close();
        reset(context);when(service.rebuild(true)).thenReturn("BUSY");
        assertThatThrownBy(()->job.run(null)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(context);
    }
    @Test void recurringFailureIsRetriedAndDisabledReadersNeverWrite() throws Exception {
        var service=mock(SearchRebuildService.class);var context=mock(ConfigurableApplicationContext.class);
        var disabled=new SearchRebuildJob(service,context,false,"serve");disabled.run(null);disabled.reconcile();verifyNoInteractions(service);
        var enabled=new SearchRebuildJob(service,context,true,"serve");
        when(service.rebuild(false)).thenThrow(new java.io.IOException("offline")).thenReturn("generation");
        enabled.run(null);enabled.reconcile();verify(service,times(2)).rebuild(false);
    }
}

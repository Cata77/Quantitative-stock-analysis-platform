
package com.quantplatform.screener;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.quantplatform.screener.api.*;
import com.quantplatform.screener.ranking.*;
import com.quantplatform.screener.search.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class ApiDependencyTest {
    @Test void dependencyFailuresReturn503WithoutLeakingDetails() throws Exception {
        var rankings=mock(RankingService.class);var search=mock(CompanySearchService.class);
        when(rankings.findRankings(any(),anyInt(),anyInt(),any()))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database address"));
        when(search.search(anyString(),anyInt(),anyInt())).thenThrow(new SearchUnavailableException(new java.io.IOException("private host")));
        var mvc=MockMvcBuilders.standaloneSetup(new ScreenerController(rankings,search)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/screener/rankings")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/screener/search").param("q","test")).andExpect(status().isServiceUnavailable());
    }
}

package com.quantplatform.portfolio.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quantplatform.portfolio.holding.PortfolioHolding;
import com.quantplatform.portfolio.holding.PortfolioHoldingRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PortfolioControllerIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("3df82c8f-f66a-46f4-9908-30aa585bdfc8");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("d571da24-81a5-4a01-b404-4e9178090826");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PortfolioHoldingRepository holdingRepository;

    @BeforeEach
    void clearHoldings() {
        holdingRepository.deleteAll();
    }

    @Test
    void createsListsUpdatesAndDeletesHolding() throws Exception {
        mockMvc.perform(post("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker":"aapl",
                                  "quantity":2.5000,
                                  "entryPrice":195.1250,
                                  "purchasedAt":"2025-05-10T12:30:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", not(blankOrNullString())))
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.quantity").value(2.5))
                .andExpect(jsonPath("$.entryPrice").value(195.125));

        PortfolioHolding created = holdingRepository.findAll().getFirst();

        mockMvc.perform(get("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(created.getId().toString()));

        mockMvc.perform(put("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker":"msft",
                                  "quantity":4.0000,
                                  "entryPrice":420.7500,
                                  "purchasedAt":"2025-06-01T09:15:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("MSFT"))
                .andExpect(jsonPath("$.quantity").value(4.0));

        mockMvc.perform(get("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryPrice").value(420.75));

        mockMvc.perform(delete("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void isolatesHoldingsByAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHoldingJson()))
                .andExpect(status().isCreated());

        PortfolioHolding created = holdingRepository.findAll().getFirst();

        mockMvc.perform(get("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, OTHER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, OTHER_USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Portfolio holding was not found"));

        mockMvc.perform(delete("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, OTHER_USER_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/portfolio/{holdingId}", created.getId())
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingAndMalformedAuthenticatedIdentity() throws Exception {
        mockMvc.perform(get("/portfolio"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail")
                        .value("A valid authenticated user identity is required"));

        mockMvc.perform(get("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesFinancialFieldsAndTicker() throws Exception {
        mockMvc.perform(post("/portfolio")
                        .header(PortfolioController.AUTHENTICATED_USER_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker":"invalid ticker",
                                  "quantity":0,
                                  "entryPrice":-1,
                                  "purchasedAt":"2099-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.ticker").exists())
                .andExpect(jsonPath("$.violations.quantity").exists())
                .andExpect(jsonPath("$.violations.entryPrice").exists())
                .andExpect(jsonPath("$.violations.purchasedAt").exists());
    }

    private String validHoldingJson() {
        return """
                {
                  "ticker":"NVDA",
                  "quantity":3.0000,
                  "entryPrice":145.5000,
                  "purchasedAt":"2025-04-20T10:00:00Z"
                }
                """;
    }
}

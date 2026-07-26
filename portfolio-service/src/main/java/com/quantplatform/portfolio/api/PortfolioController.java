package com.quantplatform.portfolio.api;

import com.quantplatform.portfolio.holding.PortfolioHolding;
import com.quantplatform.portfolio.service.InvalidAuthenticatedUserException;
import com.quantplatform.portfolio.service.PortfolioService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/portfolio")
public class PortfolioController {

    static final String AUTHENTICATED_USER_HEADER = "X-Authenticated-User-Id";

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    List<HoldingResponse> findAll(
            @RequestHeader(name = AUTHENTICATED_USER_HEADER, required = false) String userHeader
    ) {
        return portfolioService.findAll(authenticatedUserId(userHeader)).stream()
                .map(HoldingResponse::from)
                .toList();
    }

    @PostMapping
    ResponseEntity<HoldingResponse> create(
            @RequestHeader(name = AUTHENTICATED_USER_HEADER, required = false) String userHeader,
            @Valid @RequestBody HoldingRequest request
    ) {
        PortfolioHolding holding = portfolioService.create(
                authenticatedUserId(userHeader),
                request.ticker(),
                request.quantity(),
                request.entryPrice(),
                request.purchasedAt());
        HoldingResponse response = HoldingResponse.from(holding);
        return ResponseEntity.created(URI.create("/portfolio/" + response.id())).body(response);
    }

    @GetMapping("/{holdingId}")
    HoldingResponse find(
            @RequestHeader(name = AUTHENTICATED_USER_HEADER, required = false) String userHeader,
            @PathVariable UUID holdingId
    ) {
        return HoldingResponse.from(
                portfolioService.find(authenticatedUserId(userHeader), holdingId));
    }

    @PutMapping("/{holdingId}")
    HoldingResponse update(
            @RequestHeader(name = AUTHENTICATED_USER_HEADER, required = false) String userHeader,
            @PathVariable UUID holdingId,
            @Valid @RequestBody HoldingRequest request
    ) {
        return HoldingResponse.from(portfolioService.update(
                authenticatedUserId(userHeader),
                holdingId,
                request.ticker(),
                request.quantity(),
                request.entryPrice(),
                request.purchasedAt()));
    }

    @DeleteMapping("/{holdingId}")
    ResponseEntity<Void> delete(
            @RequestHeader(name = AUTHENTICATED_USER_HEADER, required = false) String userHeader,
            @PathVariable UUID holdingId
    ) {
        portfolioService.delete(authenticatedUserId(userHeader), holdingId);
        return ResponseEntity.noContent().build();
    }

    private UUID authenticatedUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new InvalidAuthenticatedUserException();
        }
        try {
            return UUID.fromString(headerValue);
        } catch (IllegalArgumentException exception) {
            throw new InvalidAuthenticatedUserException();
        }
    }
}

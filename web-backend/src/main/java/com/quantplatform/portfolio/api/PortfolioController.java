package com.quantplatform.portfolio.api;

import com.quantplatform.portfolio.holding.PortfolioHolding;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
            @AuthenticationPrincipal Jwt principal
    ) {
        return portfolioService.findAll(UUID.fromString(principal.getSubject())).stream()
                .map(HoldingResponse::from)
                .toList();
    }

    @PostMapping
    ResponseEntity<HoldingResponse> create(
            @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody HoldingRequest request
    ) {
        PortfolioHolding holding = portfolioService.create(
                UUID.fromString(principal.getSubject()),
                request.ticker(),
                request.quantity(),
                request.entryPrice(),
                request.purchasedAt());
        HoldingResponse response = HoldingResponse.from(holding);
        return ResponseEntity.created(URI.create("/portfolio/" + response.id())).body(response);
    }

    @GetMapping("/{holdingId}")
    HoldingResponse find(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID holdingId
    ) {
        return HoldingResponse.from(
                portfolioService.find(UUID.fromString(principal.getSubject()), holdingId));
    }

    @PutMapping("/{holdingId}")
    HoldingResponse update(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID holdingId,
            @Valid @RequestBody HoldingRequest request
    ) {
        return HoldingResponse.from(portfolioService.update(
                UUID.fromString(principal.getSubject()),
                holdingId,
                request.ticker(),
                request.quantity(),
                request.entryPrice(),
                request.purchasedAt()));
    }

    @DeleteMapping("/{holdingId}")
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID holdingId
    ) {
        portfolioService.delete(UUID.fromString(principal.getSubject()), holdingId);
        return ResponseEntity.noContent().build();
    }

}

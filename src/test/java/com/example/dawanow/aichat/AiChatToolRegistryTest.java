package com.example.dawanow.aichat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dawanow.dtos.response.PaginatedResponse;
import com.example.dawanow.dtos.response.ProductResponse;
import com.example.dawanow.entity.Pharmacy;
import com.example.dawanow.repo.PharmacyRepository;
import com.example.dawanow.service.CurrentUserProvider;
import com.example.dawanow.service.MedicineRequestService;
import com.example.dawanow.service.OrderService;
import com.example.dawanow.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class AiChatToolRegistryTest {

    private ProductService productService;
    private PharmacyRepository pharmacyRepository;
    private AiChatToolRegistry registry;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        pharmacyRepository = mock(PharmacyRepository.class);
        registry = new AiChatToolRegistry(
                productService,
                pharmacyRepository,
                mock(OrderService.class),
                mock(MedicineRequestService.class),
                mock(CurrentUserProvider.class)
        );
        when(productService.searchProducts(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PaginatedResponse<>(List.of(), 0, 50, 0, 0, true));
    }

    @Test
    void searchesExistingCatalogAndBuildsConfirmedCartSuggestion() {
        ProductResponse panadol = new ProductResponse(
                17L,
                "Panadol Extra 24 Tablets",
                "Panadol Extra",
                "500 mg",
                "24",
                "tablets",
                new BigDecimal("45.00"),
                "Paracetamol",
                "Analgesics",
                1L,
                "Pain Relief",
                "GSK",
                "ORAL.SOLID",
                "Used for pain and fever relief.",
                "https://example.test/panadol.jpg"
        );
        when(productService.searchProducts(eq("Panadol"), eq("en"), any(Pageable.class)))
                .thenReturn(new PaginatedResponse<>(List.of(panadol), 0, 5, 1, 1, true));

        AiChatToolResult result = registry.resolve(
                AiChatIntent.PRODUCT_SEARCH,
                "Find Panadol",
                "en",
                null,
                null
        );

        assertEquals(List.of(17L), result.sourceProductIds());
        assertEquals("PRODUCT", result.cards().getFirst().type());
        assertEquals("ADD_TO_CART", result.actions().getFirst().type());
        assertTrue(result.actions().getFirst().requiresConfirmation());
        verify(productService).searchProducts(eq("Panadol"), eq("en"), any(Pageable.class));
    }

    @Test
    void removesBestPriceInstructionFromProductSearchQuery() {
        when(productService.searchProducts(eq("Abimol"), eq("en"), any(Pageable.class)))
                .thenReturn(new PaginatedResponse<>(List.of(), 0, 5, 0, 0, true));

        registry.resolve(
                AiChatIntent.PRODUCT_SEARCH,
                "Find Abimol and get me the best price",
                "en",
                null,
                null
        );

        verify(productService).searchProducts(eq("Abimol"), eq("en"), any(Pageable.class));
    }

    @Test
    void returnsNearbyPharmacyCardsWithoutMutatingPharmacyData() {
        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setId(9L);
        pharmacy.setName("Medsy Pharmacy");
        pharmacy.setLatitude(30.045);
        pharmacy.setLongitude(31.235);
        pharmacy.setAddress("Cairo");
        pharmacy.setPhoneNumber("01000000000");
        when(pharmacyRepository.findNearbyPharmacies(30.044, 31.234, 10.0))
                .thenReturn(List.of(pharmacy));

        AiChatToolResult result = registry.resolve(
                AiChatIntent.NEARBY_PHARMACY,
                "Find the nearest pharmacy",
                "en",
                30.044,
                31.234
        );

        assertEquals("PHARMACY", result.cards().getFirst().type());
        assertEquals(2, result.actions().size());
        assertTrue(result.actions().stream().allMatch(action -> action.requiresConfirmation()));
        verify(pharmacyRepository).findNearbyPharmacies(30.044, 31.234, 10.0);
    }
}

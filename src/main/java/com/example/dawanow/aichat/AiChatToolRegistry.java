package com.example.dawanow.aichat;

import com.example.dawanow.aichat.dto.AiChatCard;
import com.example.dawanow.aichat.dto.AiChatSuggestedAction;
import com.example.dawanow.dtos.response.MedicineRequestResponse;
import com.example.dawanow.dtos.response.OrderItemResponse;
import com.example.dawanow.dtos.response.OrderResponse;
import com.example.dawanow.dtos.response.ProductResponse;
import com.example.dawanow.entity.Customer;
import com.example.dawanow.entity.Pharmacist;
import com.example.dawanow.entity.Pharmacy;
import com.example.dawanow.entity.User;
import com.example.dawanow.repo.PharmacyRepository;
import com.example.dawanow.service.CurrentUserProvider;
import com.example.dawanow.service.MedicineRequestService;
import com.example.dawanow.service.OrderService;
import com.example.dawanow.service.ProductService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Transactional(readOnly = true)
public class AiChatToolRegistry {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_SEARCH_CANDIDATES = 5;
    private static final int CATALOG_SEARCH_PAGE_SIZE = 50;
    private static final double NEARBY_RADIUS_KM = 10.0;
    private static final Set<String> PRODUCT_QUERY_STOP_WORDS = Set.of(
            "a", "an", "any", "can", "could", "for", "me", "please", "some", "the", "you",
            "details", "information", "now", "today",
            "لو", "سمحت", "فضلك", "من"
    );
    private static final Map<String, String> ARABIC_PRODUCT_ALIASES = Map.ofEntries(
            Map.entry("بنادول", "بانادول"),
            Map.entry("باندول", "بانادول"),
            Map.entry("اكسترا", "إيكسترا"),
            Map.entry("إكسترا", "إيكسترا"),
            Map.entry("بروفن", "بروفين"),
            Map.entry("برفين", "بروفين")
    );

    private final ProductService productService;
    private final PharmacyRepository pharmacyRepository;
    private final OrderService orderService;
    private final MedicineRequestService medicineRequestService;
    private final CurrentUserProvider currentUserProvider;

    public AiChatToolRegistry(
            ProductService productService,
            PharmacyRepository pharmacyRepository,
            OrderService orderService,
            MedicineRequestService medicineRequestService,
            CurrentUserProvider currentUserProvider
    ) {
        this.productService = productService;
        this.pharmacyRepository = pharmacyRepository;
        this.orderService = orderService;
        this.medicineRequestService = medicineRequestService;
        this.currentUserProvider = currentUserProvider;
    }

    public AiChatToolResult resolve(
            AiChatIntent intent,
            String message,
            String language,
            Double latitude,
            Double longitude
    ) {
        return switch (intent) {
            case PRODUCT_SEARCH, PRODUCT_INFORMATION -> searchProductsFromMessage(message, language);
            case NEARBY_PHARMACY -> nearbyPharmacies(latitude, longitude);
            case ORDER_STATUS -> orderStatus(false, language);
            case REORDER -> orderStatus(true, language);
            case REQUEST_STATUS -> requestStatus();
            case REMINDER -> reminder(message);
            default -> AiChatToolResult.empty();
        };
    }

    public AiChatToolResult searchProductsByQueries(List<String> queries, String language) {
        AiChatToolResult result = AiChatToolResult.empty();
        Set<Long> seen = new LinkedHashSet<>();
        for (String query : queries.stream().filter(StringUtils::hasText).limit(MAX_RESULTS).toList()) {
            AiChatToolResult current = searchProducts(query, language);
            List<AiChatCard> uniqueCards = current.cards().stream()
                    .filter(card -> {
                        Object id = card.data().get("productId");
                        return id instanceof Long productId && seen.add(productId);
                    })
                    .toList();
            Set<Long> uniqueIds = uniqueCards.stream()
                    .map(card -> (Long) card.data().get("productId"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<AiChatSuggestedAction> uniqueActions = current.actions().stream()
                    .filter(action -> uniqueIds.contains(action.payload().get("productId")))
                    .toList();
            Map<String, Object> prompt = Map.of("imageProductMatches", uniqueCards.stream().map(AiChatCard::data).toList());
            result = result.merge(new AiChatToolResult(uniqueCards, List.copyOf(uniqueIds), uniqueActions, prompt));
        }
        return result;
    }

    public AiChatToolResult emergency() {
        AiChatCard card = new AiChatCard(
                "EMERGENCY",
                "Emergency assistance",
                "If this may be life-threatening, call emergency services now.",
                Map.of("phoneNumber", "123", "country", "EG")
        );
        AiChatSuggestedAction action = new AiChatSuggestedAction(
                "CALL_EMERGENCY",
                "Call 123",
                Map.of("phoneNumber", "123"),
                true
        );
        return new AiChatToolResult(
                List.of(card),
                List.of(),
                List.of(action),
                Map.of("emergency", true, "emergencyNumber", "123")
        );
    }

    private AiChatToolResult searchProducts(String query, String language) {
        if (!StringUtils.hasText(query)) {
            return new AiChatToolResult(
                    List.of(), List.of(), List.of(), Map.of("productSearch", "A product name is required")
            );
        }
        List<ProductResponse> matches = productService.searchProducts(
                query,
                language,
                PageRequest.of(0, CATALOG_SEARCH_PAGE_SIZE, Sort.by("name"))
        ).content();
        List<ProductResponse> productNameMatches = matches.stream()
                .filter(product -> startsWithProductQuery(product.name(), query)
                        || startsWithProductQuery(product.productName(), query))
                .toList();
        if (!productNameMatches.isEmpty()) {
            matches = productNameMatches;
        }
        Map<Long, ProductResponse> uniqueProducts = new LinkedHashMap<>();
        for (ProductResponse product : matches) {
            uniqueProducts.putIfAbsent(product.id(), product);
            if (uniqueProducts.size() == MAX_RESULTS) {
                break;
            }
        }
        List<ProductResponse> products = List.copyOf(uniqueProducts.values());

        List<AiChatCard> cards = products.stream().map(this::productCard).toList();
        List<Long> productIds = products.stream().map(ProductResponse::id).toList();
        List<AiChatSuggestedAction> actions = products.stream()
                .map(product -> new AiChatSuggestedAction(
                        "ADD_TO_CART",
                        "Add " + product.name() + " to cart",
                        Map.of("productId", product.id(), "quantity", 1),
                        true
                ))
                .toList();
        return new AiChatToolResult(
                cards,
                productIds,
                actions,
                Map.of("productQuery", query, "products", products.stream().map(this::productPromptData).toList())
        );
    }

    private AiChatToolResult searchProductsFromMessage(String message, String language) {
        List<String> candidates = extractProductQueries(message);
        if (candidates.isEmpty()) {
            return searchProducts("", language);
        }

        AiChatToolResult result = AiChatToolResult.empty();
        for (String candidate : candidates) {
            result = searchProducts(candidate, language);
            if (!result.cards().isEmpty()) {
                return result;
            }
        }
        return result;
    }

    private AiChatToolResult nearbyPharmacies(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return new AiChatToolResult(
                    List.of(), List.of(), List.of(), Map.of("nearbyPharmacies", "Current coordinates are required")
            );
        }
        validateCoordinates(latitude, longitude);
        List<Pharmacy> pharmacies = pharmacyRepository
                .findNearbyPharmacies(latitude, longitude, NEARBY_RADIUS_KM)
                .stream()
                .limit(MAX_RESULTS)
                .toList();

        List<AiChatCard> cards = new ArrayList<>();
        List<AiChatSuggestedAction> actions = new ArrayList<>();
        List<Map<String, Object>> promptData = new ArrayList<>();
        for (Pharmacy pharmacy : pharmacies) {
            double distance = distanceKm(latitude, longitude, pharmacy.getLatitude(), pharmacy.getLongitude());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pharmacyId", pharmacy.getId());
            putIfNotNull(data, "address", pharmacy.getAddress());
            putIfNotNull(data, "phoneNumber", pharmacy.getPhoneNumber());
            data.put("latitude", pharmacy.getLatitude());
            data.put("longitude", pharmacy.getLongitude());
            data.put("distanceKm", roundDistance(distance));
            cards.add(new AiChatCard("PHARMACY", pharmacy.getName(), pharmacy.getAddress(), Map.copyOf(data)));
            promptData.add(Map.copyOf(data));

            if (StringUtils.hasText(pharmacy.getPhoneNumber())) {
                actions.add(new AiChatSuggestedAction(
                        "CALL_PHARMACY",
                        "Call " + pharmacy.getName(),
                        Map.of("pharmacyId", pharmacy.getId(), "phoneNumber", pharmacy.getPhoneNumber()),
                        true
                ));
            }
            actions.add(new AiChatSuggestedAction(
                    "OPEN_DIRECTIONS",
                    "Directions to " + pharmacy.getName(),
                    Map.of(
                            "pharmacyId", pharmacy.getId(),
                            "latitude", pharmacy.getLatitude(),
                            "longitude", pharmacy.getLongitude()
                    ),
                    true
            ));
        }
        return new AiChatToolResult(
                List.copyOf(cards), List.of(), List.copyOf(actions), Map.of("nearbyPharmacies", promptData)
        );
    }

    private AiChatToolResult orderStatus(boolean reorder, String language) {
        User current = currentUserProvider.get();
        List<OrderResponse> orders;
        try {
            if (current instanceof Customer) {
                orders = orderService.getCurrentCustomerOrders(
                        PageRequest.of(0, MAX_RESULTS, Sort.by(Sort.Direction.DESC, "date"))
                ).content();
            } else if (current instanceof Pharmacist pharmacist && pharmacist.getPharmacy() != null) {
                orders = orderService.getPharmacyOrders(
                        pharmacist.getPharmacy().getId(),
                        PageRequest.of(0, MAX_RESULTS, Sort.by(Sort.Direction.DESC, "date"))
                ).content();
            } else {
                orders = List.of();
            }
        } catch (AccessDeniedException exception) {
            orders = List.of();
        }

        List<AiChatCard> cards = orders.stream().map(this::orderCard).toList();
        List<AiChatSuggestedAction> actions = List.of();
        if (reorder && current instanceof Customer && !orders.isEmpty()) {
            OrderResponse latest = orders.getFirst();
            List<Map<String, Object>> items = latest.items().stream()
                    .map(item -> Map.<String, Object>of(
                            "productId", item.productId(),
                            "quantity", item.quantity()
                    ))
                    .toList();
            actions = List.of(new AiChatSuggestedAction(
                    "REORDER_ITEMS",
                    "Add previous order to cart",
                    Map.of("sourceOrderId", latest.id(), "items", items),
                    true
            ));
        }

        return new AiChatToolResult(
                List.copyOf(cards),
                List.of(),
                actions,
                Map.of("orders", orders.stream().map(this::orderPromptData).toList(), "reorderRequested", reorder)
        );
    }

    private AiChatToolResult requestStatus() {
        User current = currentUserProvider.get();
        List<MedicineRequestResponse> requests;
        if (current instanceof Customer) {
            requests = medicineRequestService.getCurrentCustomerRequests(
                    PageRequest.of(0, MAX_RESULTS, Sort.by(Sort.Direction.DESC, "createdAt"))
            ).content();
        } else if (current instanceof Pharmacist) {
            requests = medicineRequestService.getCurrentPharmacyRequests(
                    PageRequest.of(0, MAX_RESULTS, Sort.by(Sort.Direction.DESC, "assignedAt"))
            ).content();
        } else {
            requests = List.of();
        }

        List<AiChatCard> cards = requests.stream()
                .map(request -> new AiChatCard(
                        "MEDICINE_REQUEST",
                        "Request #" + request.id(),
                        request.status().name(),
                        Map.of(
                                "requestId", request.id(),
                                "status", request.status().name(),
                                "createdAt", request.createdAt().toString(),
                                "itemCount", request.items().size()
                        )
                ))
                .toList();
        return new AiChatToolResult(
                cards,
                List.of(),
                List.of(),
                Map.of("medicineRequests", cards.stream().map(AiChatCard::data).toList())
        );
    }

    private AiChatToolResult reminder(String message) {
        AiChatSuggestedAction action = new AiChatSuggestedAction(
                "SET_LOCAL_REMINDER",
                "Set reminder on this device",
                Map.of("sourceText", message),
                true
        );
        return new AiChatToolResult(
                List.of(), List.of(), List.of(action), Map.of("reminderRequest", message)
        );
    }

    private AiChatCard productCard(ProductResponse product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", product.id());
        putIfNotNull(data, "productName", product.productName());
        putIfNotNull(data, "strength", product.strength());
        putIfNotNull(data, "form", product.form());
        putIfNotNull(data, "price", product.price());
        putIfNotNull(data, "scientificName", product.scientificName());
        putIfNotNull(data, "description", product.description());
        putIfNotNull(data, "imageUrl", product.imageUrl());
        return new AiChatCard("PRODUCT", product.name(), product.scientificName(), Map.copyOf(data));
    }

    private Map<String, Object> productPromptData(ProductResponse product) {
        return productCard(product).data();
    }

    private AiChatCard orderCard(OrderResponse order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id());
        data.put("status", order.status().name());
        putIfNotNull(data, "totalPrice", order.totalPrice());
        data.put("date", order.date().toString());
        data.put("itemCount", order.items().size());
        return new AiChatCard("ORDER", "Order #" + order.id(), order.status().name(), Map.copyOf(data));
    }

    private Map<String, Object> orderPromptData(OrderResponse order) {
        Map<String, Object> data = new LinkedHashMap<>(orderCard(order).data());
        data.put("items", order.items().stream().map(this::orderItemPromptData).toList());
        return Map.copyOf(data);
    }

    private Map<String, Object> orderItemPromptData(OrderItemResponse item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", item.productId());
        data.put("quantity", item.quantity());
        putIfNotNull(data, "unitPrice", item.unitPrice());
        return Map.copyOf(data);
    }

    private List<String> extractProductQueries(String message) {
        String query = message == null ? "" : message.trim();
        query = query.replaceAll(
                "(?i)\\s+(?:and\\s+)?(?:get|show|tell)?\\s*(?:me\\s+)?(?:the\\s+)?(?:best|lowest|cheapest)\\s+price\\b.*$",
                " "
        );
        query = query.replaceAll("(?i)\\b(?:ignore|disregard|forget)\\b.*$", " ");
        query = query.replaceAll(
                "(?i)\\b(find|search(?: for)?|show me|do you have|i need|tell me about|information about|what is|price of|where can i find)\\b",
                " "
        );
        query = query.replaceAll("(?i)\\b(medications|medication|medicines|medicine|products|product)\\b", " ");
        query = query.replaceAll(
                "(?i)\\b(?:currently\\s+)?available(?:\\s+(?:in|from|on|at)\\s+medsy)?\\b",
                " "
        );
        query = query.replaceAll("(?i)\\b(?:in|from|on|at)\\s+medsy\\b", " ");
        query = query.replaceAll("(?i)\\bmedsy\\b", " ");
        query = query.replaceAll(
                "(ابحث عن|دور على|اعرض لي|هل عندكم|هل يوجد|هل فيه|هل في|لو سمحت|ابحث|عندكم|عاوزة|عاوزه|عاوز|عايزة|عايز|محتاجة|محتاجه|محتاج|أحتاج|احتاج|أريد|اريد|ممكن|هات|جيب|ألاقي|الاقي|فيه|معلومات عن|ما هو|ما هي|سعر)",
                " "
        );
        query = query.replaceAll("(ادوية|أدوية|دواء|منتجات|منتج|علاج)", " ");
        query = query.replaceAll(
                "(المتاحة|المتوفرة|المتوفر|متوفرة|متوفر|متاحة|موجودة|موجود)(?:\\s+(?:في|من|على)\\s+Medsy)?",
                " "
        );
        query = query.replaceAll("(?:في|من|على)\\s+Medsy", " ");
        query = query.replaceAll("[?؟!.،]+", " ").replaceAll("\\s+", " ").trim();

        List<String> tokens = java.util.Arrays.stream(query.split("\\s+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(token -> !PRODUCT_QUERY_STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)))
                .map(this::normalizeProductToken)
                .toList();
        if (tokens.isEmpty()) {
            return List.of();
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (int length = tokens.size(); length >= 1 && candidates.size() < MAX_SEARCH_CANDIDATES; length--) {
            for (int start = 0;
                 start + length <= tokens.size() && candidates.size() < MAX_SEARCH_CANDIDATES;
                 start++) {
                String candidate = String.join(" ", tokens.subList(start, start + length));
                candidates.add(candidate.length() > 120 ? candidate.substring(0, 120).trim() : candidate);
            }
        }
        return List.copyOf(candidates);
    }

    private String normalizeProductToken(String token) {
        return ARABIC_PRODUCT_ALIASES.getOrDefault(token, token);
    }

    private boolean startsWithProductQuery(String value, String query) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(query)) {
            return false;
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        return normalizedValue.equals(normalizedQuery)
                || normalizedValue.startsWith(normalizedQuery + " ");
    }

    private void validateCoordinates(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double roundDistance(double distance) {
        return BigDecimal.valueOf(distance).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

record AiChatToolResult(
        List<AiChatCard> cards,
        List<Long> sourceProductIds,
        List<AiChatSuggestedAction> actions,
        Map<String, Object> promptData
) {
    static AiChatToolResult empty() {
        return new AiChatToolResult(List.of(), List.of(), List.of(), Map.of());
    }

    AiChatToolResult merge(AiChatToolResult other) {
        List<AiChatCard> mergedCards = new ArrayList<>(cards);
        mergedCards.addAll(other.cards);
        List<Long> mergedIds = new ArrayList<>(sourceProductIds);
        mergedIds.addAll(other.sourceProductIds);
        List<AiChatSuggestedAction> mergedActions = new ArrayList<>(actions);
        mergedActions.addAll(other.actions);
        Map<String, Object> mergedPrompt = new LinkedHashMap<>(promptData);
        mergedPrompt.putAll(other.promptData);
        return new AiChatToolResult(
                List.copyOf(mergedCards),
                List.copyOf(new LinkedHashSet<>(mergedIds)),
                List.copyOf(mergedActions),
                Map.copyOf(mergedPrompt)
        );
    }
}

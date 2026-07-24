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
import java.text.Normalizer;
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
    private static final double STRONG_PRODUCT_MATCH_SCORE = 0.82;
    private static final double NEARBY_RADIUS_KM = 10.0;
    private static final String PRODUCT_DETAILS_ENDPOINT = "/api/v1/products/%d?lang=%s";
    private static final Set<String> PRODUCT_QUERY_STOP_WORDS = Set.of(
            "a", "about", "an", "any", "are", "be", "can", "could", "definitely", "details",
            "do", "does", "exist", "exists", "for", "give", "have", "i", "information", "is",
            "it", "me", "my", "need", "not", "now", "of", "or", "please", "show", "some",
            "that", "the", "this", "to", "today", "want", "with", "would", "you",
            "أنا", "انا", "إني", "اني", "أي", "اي", "بعض", "دا", "ده", "دي", "ذلك", "عن",
            "على", "في", "لو", "ما", "مش", "من", "هو", "هي", "هذا", "هذه", "هناك", "سمحت", "فضلك"
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
            case NEARBY_PHARMACY -> nearbyPharmacies(latitude, longitude, language);
            case ORDER_STATUS -> orderStatus(false, language);
            case REORDER -> orderStatus(true, language);
            case REQUEST_STATUS -> requestStatus(language);
            case REMINDER -> reminder(message, language);
            default -> AiChatToolResult.empty();
        };
    }

    public AiChatToolResult searchProductsByQueries(List<String> queries, String language) {
        AiChatToolResult strongResult = AiChatToolResult.empty();
        AiChatToolResult firstFallback = AiChatToolResult.empty();
        Set<Long> seenStrong = new LinkedHashSet<>();
        for (String query : queries.stream().filter(StringUtils::hasText).limit(MAX_RESULTS).toList()) {
            ImageQueryMatch match = searchImageProductQuery(query, language);
            if (firstFallback.cards().isEmpty() && !match.fallback().cards().isEmpty()) {
                firstFallback = match.fallback();
            }

            int remaining = MAX_RESULTS - strongResult.cards().size();
            List<AiChatCard> uniqueCards = match.strongCards().stream()
                    .filter(card -> {
                        Object id = card.data().get("productId");
                        return id instanceof Long productId && seenStrong.add(productId);
                    })
                    .limit(remaining)
                    .toList();
            if (!uniqueCards.isEmpty()) {
                strongResult = strongResult.merge(productSubset(match.source(), uniqueCards));
            }
            if (strongResult.cards().size() == MAX_RESULTS) {
                break;
            }
        }
        return strongResult.cards().isEmpty() ? firstFallback : strongResult;
    }

    private ImageQueryMatch searchImageProductQuery(String query, String language) {
        String cleanQuery = cleanProductQuery(query);
        AiChatToolResult fallback = AiChatToolResult.empty();
        AiChatToolResult bestSource = AiChatToolResult.empty();
        List<AiChatCard> bestCards = List.of();
        double bestScore = -1.0;

        for (String candidate : imageSearchCandidates(cleanQuery)) {
            AiChatToolResult current = searchProducts(candidate, language);
            if (current.cards().isEmpty()) {
                continue;
            }
            if (fallback.cards().isEmpty()) {
                fallback = current;
            }

            double candidateBest = current.cards().stream()
                    .mapToDouble(card -> productNameSimilarity(card, cleanQuery))
                    .max()
                    .orElse(-1.0);
            if (candidateBest > bestScore) {
                bestScore = candidateBest;
                bestSource = current;
                double selectedScore = candidateBest;
                bestCards = current.cards().stream()
                        .filter(card -> Math.abs(productNameSimilarity(card, cleanQuery) - selectedScore) < 0.0001)
                        .toList();
            }
            if (bestCards.stream().anyMatch(card -> isStrongProductNameMatch(card, cleanQuery))) {
                break;
            }
        }

        List<AiChatCard> strongCards = bestCards.stream()
                .filter(card -> isStrongProductNameMatch(card, cleanQuery))
                .toList();
        return new ImageQueryMatch(bestSource, strongCards, fallback);
    }

    private List<String> imageSearchCandidates(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(query);
        List<String> tokens = java.util.Arrays.stream(query.split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
        for (String token : tokens) {
            if (candidates.size() == MAX_SEARCH_CANDIDATES) {
                break;
            }
            candidates.add(token);
        }
        for (String token : tokens) {
            if (candidates.size() == MAX_SEARCH_CANDIDATES) {
                break;
            }
            if (token.length() >= 5) {
                int fragmentLength = Math.max(3, token.length() - 2);
                candidates.add(token.substring(0, fragmentLength));
                if (candidates.size() < MAX_SEARCH_CANDIDATES) {
                    candidates.add(token.substring(token.length() - fragmentLength));
                }
            }
        }
        return candidates.stream().limit(MAX_SEARCH_CANDIDATES).toList();
    }

    private String cleanProductQuery(String query) {
        String cleanQuery = query == null ? "" : query
                .replaceAll("^[\\s\\\"'«»]+|[\\s\\\"'«»]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleanQuery.length() > 120 ? cleanQuery.substring(0, 120).trim() : cleanQuery;
    }

    private AiChatToolResult productSubset(AiChatToolResult source, List<AiChatCard> cards) {
        Set<Long> productIds = cards.stream()
                .map(card -> (Long) card.data().get("productId"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<AiChatSuggestedAction> actions = source.actions().stream()
                .filter(action -> productIds.contains(action.payload().get("productId")))
                .toList();
        return new AiChatToolResult(
                cards,
                List.copyOf(productIds),
                actions,
                Map.of("imageProductMatches", cards.stream().map(AiChatCard::data).toList())
        );
    }

    private boolean isStrongProductNameMatch(AiChatCard card, String query) {
        String normalizedQuery = normalizeCatalogName(query);
        Object productName = card.data().get("productName");
        String normalizedProductName = productName instanceof String value
                ? normalizeCatalogName(value)
                : "";
        String normalizedTitle = normalizeCatalogName(card.title());
        int distance = Math.min(
                levenshteinDistance(normalizedQuery, normalizedProductName),
                levenshteinDistance(normalizedQuery, normalizedTitle)
        );
        return distance <= 1 || productNameSimilarity(card, query) >= STRONG_PRODUCT_MATCH_SCORE;
    }

    private String normalizeCatalogName(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private double productNameSimilarity(AiChatCard card, String query) {
        String normalizedQuery = normalizeCatalogName(query);
        Object productName = card.data().get("productName");
        double productNameScore = productName instanceof String value
                ? similarity(normalizedQuery, normalizeCatalogName(value))
                : 0.0;
        return Math.max(productNameScore, similarity(normalizedQuery, normalizeCatalogName(card.title())));
    }

    private double similarity(String left, String right) {
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 1.0;
        }
        return 1.0 - ((double) levenshteinDistance(left, right) / maxLength);
    }

    private int levenshteinDistance(String left, String right) {
        if (!StringUtils.hasText(left)) {
            return right.length();
        }
        if (!StringUtils.hasText(right)) {
            return left.length();
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitutionCost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitutionCost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    public AiChatToolResult emergency(String language) {
        boolean arabic = "ar".equals(language);
        AiChatCard card = new AiChatCard(
                "EMERGENCY",
                arabic ? "مساعدة طارئة" : "Emergency assistance",
                arabic
                        ? "إذا كانت الحالة قد تهدد الحياة، اتصل بالإسعاف فورًا."
                        : "If this may be life-threatening, call emergency services now.",
                Map.of("phoneNumber", "123", "country", "EG")
        );
        AiChatSuggestedAction action = new AiChatSuggestedAction(
                "CALL_EMERGENCY",
                arabic ? "اتصل بـ 123" : "Call 123",
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

    public AiChatToolResult emergency() {
        return emergency("en");
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
        if (matches.isEmpty()) {
            String alternateLanguage = "ar".equalsIgnoreCase(language) ? "en" : "ar";
            matches = productService.searchProducts(
                    query,
                    alternateLanguage,
                    PageRequest.of(0, CATALOG_SEARCH_PAGE_SIZE, Sort.by("name"))
            ).content();
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
        boolean arabic = "ar".equalsIgnoreCase(language);
        String responseLanguage = arabic ? "ar" : "en";
        List<AiChatSuggestedAction> actions = new ArrayList<>();
        for (ProductResponse product : products) {
            actions.add(new AiChatSuggestedAction(
                        "ADD_TO_CART",
                        arabic
                                ? "أضف " + product.name() + " إلى السلة"
                                : "Add " + product.name() + " to cart",
                        Map.of("productId", product.id(), "quantity", 1),
                        true
            ));
            actions.add(new AiChatSuggestedAction(
                    "VIEW_PRODUCT_DETAILS",
                    arabic
                            ? "اعرض تفاصيل " + product.name()
                            : "View " + product.name() + " details",
                    Map.of(
                            "productId", product.id(),
                            "method", "GET",
                            "endpoint", PRODUCT_DETAILS_ENDPOINT.formatted(product.id(), responseLanguage),
                            "language", responseLanguage
                    ),
                    false
            ));
        }
        return new AiChatToolResult(
                cards,
                productIds,
                actions,
                Map.of("productQuery", query, "products", products.stream().map(this::productPromptData).toList())
        );
    }

    private AiChatToolResult searchProductsFromMessage(String message, String language) {
        String originalQuery = extractProductQuery(message);
        if (!StringUtils.hasText(originalQuery)) {
            return searchProducts("", language);
        }

        ImageQueryMatch match = searchImageProductQuery(originalQuery, language);
        AiChatToolResult result = match.strongCards().isEmpty()
                ? match.fallback()
                : productSubset(match.source(), match.strongCards().stream().limit(MAX_RESULTS).toList());
        if (result.cards().isEmpty()) {
            return result;
        }
        Map<String, Object> promptData = new LinkedHashMap<>(result.promptData());
        promptData.put("productQuery", originalQuery);
        return new AiChatToolResult(
                result.cards(),
                result.sourceProductIds(),
                result.actions(),
                Map.copyOf(promptData)
        );
    }

    private AiChatToolResult nearbyPharmacies(Double latitude, Double longitude, String language) {
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
                        "ar".equals(language)
                                ? "اتصل بصيدلية " + pharmacy.getName()
                                : "Call " + pharmacy.getName(),
                        Map.of("pharmacyId", pharmacy.getId(), "phoneNumber", pharmacy.getPhoneNumber()),
                        true
                ));
            }
            actions.add(new AiChatSuggestedAction(
                    "OPEN_DIRECTIONS",
                    "ar".equals(language)
                            ? "الاتجاهات إلى صيدلية " + pharmacy.getName()
                            : "Directions to " + pharmacy.getName(),
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

        List<AiChatCard> cards = orders.stream().map(order -> orderCard(order, language)).toList();
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
                    "ar".equals(language) ? "أضف الطلب السابق إلى السلة" : "Add previous order to cart",
                    Map.of("sourceOrderId", latest.id(), "items", items),
                    true
            ));
        }

        return new AiChatToolResult(
                List.copyOf(cards),
                List.of(),
                actions,
                Map.of(
                        "orders", orders.stream().map(order -> orderPromptData(order, language)).toList(),
                        "reorderRequested", reorder
                )
        );
    }

    private AiChatToolResult requestStatus(String language) {
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
                        "ar".equals(language) ? "طلب #" + request.id() : "Request #" + request.id(),
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

    private AiChatToolResult reminder(String message, String language) {
        AiChatSuggestedAction action = new AiChatSuggestedAction(
                "SET_LOCAL_REMINDER",
                "ar".equals(language) ? "اضبط تذكيرًا على هذا الجهاز" : "Set reminder on this device",
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

    private AiChatCard orderCard(OrderResponse order, String language) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id());
        data.put("status", order.status().name());
        putIfNotNull(data, "totalPrice", order.totalPrice());
        data.put("date", order.date().toString());
        data.put("itemCount", order.items().size());
        return new AiChatCard(
                "ORDER",
                "ar".equals(language) ? "طلب #" + order.id() : "Order #" + order.id(),
                order.status().name(),
                Map.copyOf(data)
        );
    }

    private Map<String, Object> orderPromptData(OrderResponse order, String language) {
        Map<String, Object> data = new LinkedHashMap<>(orderCard(order, language).data());
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

    private String extractProductQuery(String message) {
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
                .toList();
        if (tokens.isEmpty()) {
            return "";
        }
        String productQuery = String.join(" ", tokens);
        return productQuery.length() > 120 ? productQuery.substring(0, 120).trim() : productQuery;
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

    private record ImageQueryMatch(
            AiChatToolResult source,
            List<AiChatCard> strongCards,
            AiChatToolResult fallback
    ) {
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

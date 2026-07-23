package com.example.dawanow.aichat;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AiChatModelRouter {

    private static final Set<String> MEDICAL_TERMS = Set.of(
            "symptom", "symptoms", "headache", "dizzy", "dizziness", "fever", "pain", "rash",
            "vomit", "nausea", "pregnant", "allergy", "dose", "dosage", "side effect", "interaction",
            "diagnose", "treatment", "صداع", "دوخة", "حرارة", "ألم", "الم", "طفح", "حامل",
            "حساسية", "جرعة", "اعراض", "أعراض", "تداخل"
    );
    private static final Set<String> EMERGENCY_TERMS = Set.of(
            "chest pain", "cannot breathe", "can't breathe", "difficulty breathing", "unconscious",
            "severe bleeding", "stroke", "overdose", "suicide", "kill myself", "ألم في الصدر",
            "الم في الصدر", "مش قادر اتنفس", "صعوبة في التنفس", "فاقد الوعي", "نزيف شديد",
            "جرعة زائدة", "انتحار"
    );

    private final AiChatProperties properties;

    public AiChatModelRouter(AiChatProperties properties) {
        this.properties = properties;
    }

    public AiChatRoute route(String message, boolean hasImage) {
        return route(message, hasImage, detectIntent(message));
    }

    public AiChatRoute route(String message, boolean hasImage, AiChatIntent classifiedIntent) {
        if (hasImage) {
            return new AiChatRoute(AiChatIntent.IMAGE_ANALYSIS, properties.visionModel(), true);
        }

        AiChatIntent intent = classifiedIntent == null ? detectIntent(message) : classifiedIntent;
        boolean complex = intent == AiChatIntent.MEDICAL_INFORMATION
                || message.length() > 240
                || message.lines().count() > 2;
        String model = complex ? properties.complexModel() : properties.haikuModel();
        return new AiChatRoute(intent, model, complex || isEmergency(message));
    }

    public AiChatIntent detectIntent(String message) {
        String text = normalize(message);
        if (containsAny(text, "nearby pharmacy", "nearest pharmacy", "pharmacies near", "صيدلية قريبة",
                "اقرب صيدلية", "أقرب صيدلية")) {
            return AiChatIntent.NEARBY_PHARMACY;
        }
        if (containsAny(text, "reorder", "order again", "usual medicine", "اطلب تاني", "إعادة الطلب")) {
            return AiChatIntent.REORDER;
        }
        if (containsAny(text, "track order", "order status", "where is my order", "حالة الطلب", "تتبع الطلب")) {
            return AiChatIntent.ORDER_STATUS;
        }
        if (containsAny(text, "request status", "track request", "offer status", "my offers", "حالة الطلبية",
                "العروض")) {
            return AiChatIntent.REQUEST_STATUS;
        }
        if (containsAny(text, "remind me", "set reminder", "medicine reminder", "فكرني", "تذكير")) {
            return AiChatIntent.REMINDER;
        }
        if (containsTerm(text, MEDICAL_TERMS)) {
            return AiChatIntent.MEDICAL_INFORMATION;
        }
        if (containsAny(text, "tell me about", "information about", "what is", "side effects of",
                "معلومات عن", "ما هو", "ما هي")) {
            return AiChatIntent.PRODUCT_INFORMATION;
        }
        if (containsAny(text, "find ", "search ", "search for", "show me", "do you have", "price of",
                "where can i find", "ابحث", "دور على", "عندكم", "سعر")) {
            return AiChatIntent.PRODUCT_SEARCH;
        }
        return AiChatIntent.GENERAL;
    }

    public boolean isEmergency(String message) {
        return containsTerm(normalize(message), EMERGENCY_TERMS);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTerm(String text, Set<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }
}

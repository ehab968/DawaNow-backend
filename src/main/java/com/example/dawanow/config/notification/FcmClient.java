package com.example.dawanow.config.notification;


import com.example.dawanow.entity.notification.DeviceToken;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Wraps the firebase-admin SDK. FCM's sendEachForMulticast accepts up to
 * 500 tokens per call — DispatchResult tells the caller which specific
 * tokens permanently failed (UNREGISTERED / INVALID_ARGUMENT) so they can
 * be deactivated, versus which failed transiently and should be retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmClient {

    private static final int MAX_TOKENS_PER_BATCH = 500;

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;

    public DispatchResult send(List<DeviceToken> tokens, String title, String body,
                               Map<String, String> dataPayload) {
        DispatchResult result = new DispatchResult();
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            log.info("Firebase is disabled; skipping delivery to {} device token(s)", tokens.size());
            result.transientFailures.addAll(tokens);
            return result;
        }

        for (int i = 0; i < tokens.size(); i += MAX_TOKENS_PER_BATCH) {
            List<DeviceToken> batch = tokens.subList(i, Math.min(i + MAX_TOKENS_PER_BATCH, tokens.size()));
            dispatchBatch(firebaseMessaging, batch, title, body, dataPayload, result);
        }
        return result;
    }

    private void dispatchBatch(FirebaseMessaging firebaseMessaging, List<DeviceToken> batch, String title, String body,
                               Map<String, String> dataPayload, DispatchResult result) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(batch.stream().map(DeviceToken::getFcmToken).toList())
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putAllData(dataPayload)
                .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            List<SendResponse> responses = response.getResponses();

            for (int i = 0; i < responses.size(); i++) {
                SendResponse sendResponse = responses.get(i);
                DeviceToken token = batch.get(i);

                if (sendResponse.isSuccessful()) {
                    result.succeeded.add(token);
                } else {
                    FirebaseMessagingException ex = sendResponse.getException();
                    MessagingErrorCode code = ex != null ? ex.getMessagingErrorCode() : null;
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                        result.deadTokens.add(token);
                    } else {
                        result.transientFailures.add(token);
                    }
                    log.warn("FCM send failed for token id={}, code={}", token.getId(), code);
                }
            }
        } catch (FirebaseMessagingException e) {
            // whole-batch failure (e.g. FCM temporarily unavailable) — treat every token as transient
            log.error("FCM batch send failed", e);
            result.transientFailures.addAll(batch);
        }
    }

    public static class DispatchResult {
        public final List<DeviceToken> succeeded = new java.util.ArrayList<>();
        public final List<DeviceToken> deadTokens = new java.util.ArrayList<>();
        public final List<DeviceToken> transientFailures = new java.util.ArrayList<>();
    }

    public DryRunResult sendDryRun(List<String> tokens, String title, String body) {
        DryRunResult result = new DryRunResult();
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            throw new IllegalStateException("Firebase is disabled; dry-run delivery is unavailable");
        }

        for (int i = 0; i < tokens.size(); i += MAX_TOKENS_PER_BATCH) {
            List<String> batch = tokens.subList(i, Math.min(i + MAX_TOKENS_PER_BATCH, tokens.size()));
            dryRunBatch(firebaseMessaging, batch, title, body, result);
        }
        return result;
    }

    private void dryRunBatch(FirebaseMessaging firebaseMessaging, List<String> batch, String title, String body, DryRunResult result) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(batch)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            // second arg = dryRun: FCM validates and would-be-processes the
            // request but never actually delivers it to a device
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message, true);
            List<SendResponse> responses = response.getResponses();

            log.info("Dry run: {} would succeed, {} would fail",
                    response.getSuccessCount(), response.getFailureCount());

            for (int i = 0; i < responses.size(); i++) {
                SendResponse sendResponse = responses.get(i);
                String token = batch.get(i);

                if (sendResponse.isSuccessful()) {
                    // credentials + project config are correct, and this token
                    // is well-formed enough that FCM would accept it
                    result.wouldSucceed.add(token);
                } else {
                    FirebaseMessagingException ex = sendResponse.getException();
                    MessagingErrorCode code = ex != null ? ex.getMessagingErrorCode() : null;
                    result.wouldFail.put(token, code != null ? code.name() : "UNKNOWN");
                    log.warn("Dry run rejected token={}, code={}", token, code);
                }
            }
        } catch (FirebaseMessagingException e) {
            // this is the interesting failure mode for setup validation —
            // it means the request never even got to per-token evaluation,
            // i.e. your service account / credentials / project config is wrong,
            // not the tokens
            log.error("Dry run request itself failed — check service account "
                    + "credentials and Firebase project configuration", e);
            throw new IllegalStateException("FCM dry run failed before per-token evaluation", e);
        }
    }

    public static class DryRunResult {
        public final List<String> wouldSucceed = new java.util.ArrayList<>();
        // token -> FCM MessagingErrorCode name, e.g. "INVALID_ARGUMENT", "UNREGISTERED"
        public final Map<String, String> wouldFail = new java.util.LinkedHashMap<>();
    }
}

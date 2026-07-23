# Medsy AI Chat Postman suite

Import these two files into Postman:

- `Medsy-AI-Chat.postman_collection.json`
- `Medsy-AI-Chat.local.postman_environment.json`

## Setup

1. Select the **Medsy AI Chat - Local** environment.
2. Set `customerEmail` and `customerPassword` to a verified Medsy patient/customer account.
3. Set `pharmacistEmail` and `pharmacistPassword` to a verified Medsy pharmacist account.
4. Set `sbgApiKey` to a student gateway key beginning with `sbg_`. Never commit or export the populated environment.
5. Change `medsyBaseUrl` if the Java backend is not running on `http://localhost:8080`.
6. For the backend multimodal request, select a genuine PNG or JPEG in the `image` form-data field before sending.

## Run order

Postman collection runs are intentionally split by role because each login replaces the collection access token.

1. Run **Login as patient/customer**.
2. Run folders **01**, **02**, and **04**.
3. Run **Login as pharmacist**.
4. Run folder **03**.
5. Run folder **05** after setting `sbgApiKey` to verify every configured ITI model directly.

If Codex needs to run the live probes and role scenarios for you, create an ignored file named `chat-test.local` in the backend root with `MEDSY_BASE_URL`, `SBG_API_KEY`, `CUSTOMER_EMAIL`, `CUSTOMER_PASSWORD`, `PHARMACIST_EMAIL`, and `PHARMACIST_PASSWORD`. Do not send the portal password, commit this file, or use production patient accounts. Delete it after testing.

## What is covered

- JWT enforcement and request validation boundaries.
- Empty and oversized messages, oversized/forged history, invalid language, and invalid coordinates.
- Patient product search, nearby pharmacies, order status, reorder confirmation, Arabic responses, and tool-data integrity.
- Pharmacist order/request scoping and patient-only action isolation.
- Prompt injection, fabricated assistant history, obfuscated self-harm, poisoning, dangerous dosing, Arabic emergencies, and multi-intent messages.
- PNG/JPEG backend multimodal contract.
- Direct live probes for fast text, complex text, safeguard, and vision models.

Two requests are labelled **expected to expose gap**. Their assertions describe production-grade behavior that the current single-intent/local-keyword implementation does not reliably provide. A failing assertion is evidence of a known product gap, not a Postman defect.

## Interpreting results

- A `200` alone is not a pass. Each request checks structured response behavior, grounding, action confirmation, or safety properties.
- `providerStatus = NOT_CONFIGURED` means the Medsy endpoint used its deterministic fallback and did not test a live model.
- Direct model probes must return HTTP 200 and non-empty `output_text`. A 403/422 generally means that model is not in the student's live allowlist or its payload contract differs.
- Live safety output is probabilistic. Re-run the adversarial folder multiple times and preserve failed examples for regression evaluation.

Do not treat this suite as proof of 100% medical correctness. Production readiness requires a larger versioned evaluation set, clinician-reviewed expected outcomes, fail-closed safety behavior, red-team testing, and monitored human escalation.

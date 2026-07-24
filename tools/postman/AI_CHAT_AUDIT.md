# Medsy AI chat backend audit

Audit date: 2026-07-23

## Executive result

The implementation is a useful prototype with authenticated endpoints, bounded input, deterministic read-only tools, confirmation-gated actions, structured model output, role-aware order/request lookup, image signature checks, and a fail-closed safeguard enabled by default. It cannot honestly guarantee 100% medical correctness or fully dynamic behavior for arbitrary language.

The largest remaining gaps are plaintext transport to the gateway, committed secrets, single-intent routing, client-controlled conversation history, missing output-side medical validation, and limited patient/pharmacist context.

## Current model routing and live result

| Capability | Configured default | Used for | Direct ITI result on 2026-07-23 |
| --- | --- | --- | --- |
| Fast/classifier | `us.meta.llama3-3-70b-instruct-v1:0` | Intent classification and general/product/order requests | HTTP 200 with structured JSON |
| Complex text | `openai.gpt-oss-20b-1:0` | Medical intent, messages over 240 characters, or more than two lines | HTTP 200 with structured JSON at 300 and 900 tokens |
| Vision | `qwen.qwen3-vl-235b-a22b` | Image requests | HTTP 200 with structured image JSON |
| Safeguard | `openai.gpt-oss-safeguard-20b` | Safety-relevant text and image prompts | HTTP 200 with blocking JSON at 300 tokens |

The authenticated ITI `/api/v1/student/me` response confirmed the account's 26-model allowlist. The previous fast default, `anthropic.claude-haiku-4-5-20251001-v1:0`, was allowlisted but repeatedly returned HTTP 502. `us.amazon.nova-2-lite-v1:0` was also allowlisted but returned `REGION_NOT_ALLOWED`; neither is used by the corrected defaults.

## Corrections applied during the audit

- Serialize the Jackson request tree to a JSON string before passing it to Spring `RestClient`. Previously Spring Boot 4 serialized `ObjectNode` metadata instead of `model_id` and `messages`, so every integrated provider call returned HTTP 422.
- Replace the unhealthy fast-model default with the working Llama model.
- Raise classifier and safeguard capacity from 120 to 300 tokens. The safeguard exhausted 120 tokens and returned empty `output_text`.
- Enable safety by default, apply it to both text and image requests, and fail closed on safeguard errors or malformed output.
- Expose gateway, model, safety, and timeout settings through Docker Compose.
- Ground natural-language English and Arabic product searches in verified catalog cards, with bounded fallback candidates, deduplicated IDs, and deterministic result counts.
- Recognize common Egyptian-Arabic product requests and spelling variants, and answer in Arabic whenever the current message is Arabic.

## Dynamic behavior already implemented

- Model classification with deterministic local fallback routing.
- Product search with grounded cards and confirmation-gated add-to-cart suggestions.
- Nearby-pharmacy lookup from coordinates, limited to five results.
- Role-scoped order and medicine-request status.
- Patient-only reorder and device-local reminder suggestions without server-side mutation.
- Vision analysis followed by at most five catalog searches.
- Patient/pharmacist role labels, English/Arabic selection, and at most twelve history messages.

## Remaining findings

### P0 - Release blockers

1. **No output-side safety validation.** The main model can still return unsafe dosing, diagnosis, or interaction claims. Prompt instructions are not a security boundary.
2. **Gateway transport is plaintext HTTP.** HTTPS timed out during the audit. Sending an API key or patient text over plaintext is unacceptable for production.
3. **Secrets are committed.** Tracked application configuration contains a real-looking mail credential and fixed JWT signing secret. Rotate them, remove them from tracked configuration/history, and inject secrets securely.
4. **No PHI governance.** There is no explicit redaction, consent, retention, audit, or data-processing policy for history sent to the external gateway.

### P1 - Correctness and scale gaps

1. **One intent/tool per turn.** A request such as "find Panadol and the nearest pharmacy carrying it" cannot plan and execute both operations.
2. **Tools ignore prior turns.** The model sees history, but the tool registry receives only the latest message, so follow-ups such as "how much is the second one?" are unreliable.
3. **History is client-controlled.** A caller can fabricate assistant turns. Server-owned conversations are needed for integrity.
4. **Role personalization is shallow.** Role affects data scope and the prompt label, but there is no explicit patient-versus-pharmacist policy matrix or consented clinical profile.
5. **No resilient provider strategy.** There is no retry/backoff, circuit breaker, fallback model, health state, bulkhead, or startup availability check.
6. **No cost/quality observability.** Provider latency, token usage, safety decisions, tool execution, cost, and outcomes are not captured as privacy-safe metrics.
7. **OpenAPI auth metadata is inaccurate.** AI endpoints advertise basic auth while runtime security uses bearer JWT.
8. **History has no total token budget.** Up to twelve large messages can be forwarded without tokenizer-aware truncation.

### P2 - Test and product maturity

1. The AI-chat suite still lacks a complete provider transport, error, timeout, concurrency, and fallback matrix.
2. The provider integrations still need automated live canary checks that use non-production prompts and strict budget limits.
3. There are no clinician-reviewed medical evaluations, multilingual red-team corpus, load tests, or statistically repeated nondeterministic evaluations in CI.
4. There is no server-owned conversation model, streaming, human handoff workflow, or versioned prompt/policy registry.

## Verification performed

- Reviewed the controller, DTOs, service, router, gateway client, tool registry, security, exception handling, and AI-chat tests.
- Confirmed the live ITI endpoints, bearer-key contract, response field, account model allowlist, and all four corrected model calls.
- Reproduced the integrated HTTP 422, captured the malformed outbound request, and implemented the serialization correction.
- Confirmed the final Docker image compiles successfully with automated tests skipped, as requested.
- Live-verified grounded English and Arabic product search, exact/partial match counts, deterministic no-match responses, confirmation-gated actions, and JPEG image analysis.
- Created an importable Postman collection covering validation, roles, grounding, prompt injection, dangerous dosing, emergencies, multi-intent behavior, images, and all four configured live models.

## Definition of ready

A defensible release target is zero critical safety failures in a versioned clinician-reviewed evaluation set, deterministic authorization and grounding assertions, documented residual risk, human escalation, live monitoring, rollback, and repeated red-team testing for both patient and pharmacist roles. This is stronger and more honest than claiming 100% correctness for unrestricted natural-language requests.

# Delegated Search Backend

Release baseline: Aster 2.4.1 / versionCode 59.

Aster can delegate real-time search for Chat Completions models to a separately selected Responses model. The current implementation is designed for Grok-compatible gateways verified to support `web_search` and `x_search`.

- Responses chat models keep their native `web_search` path.
- Chat models receive an Aster `search_web` function when a search backend is configured.
- Only the self-contained search query is sent to the search backend, not the full conversation.
- Search results are returned to the original model as tool output; the original model remains the final-answer model.
- Server-side sources and URL citations are preserved as `ChatCitation`.
- Identical searches are reused within one user turn, and delegated search is capped at four real backend requests per turn.
- Skills, file creation, and delegated search can coexist in Chat tool calls.

Configuration adds `activeSearchProfileId`, per-profile `searchModel`, and `allowXSearch`. Old configs migrate with search disabled until a search model is selected.

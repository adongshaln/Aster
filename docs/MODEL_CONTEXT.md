# Per-model context budgets

Implementation checkpoint: CI pending.

Settings → API → default chat model → model context. Limits are keyed by exact model ID within each API profile, independently of the model list cache. The window (4,096–2,097,152 tokens) includes output; the output limit and a 5% / minimum 512-token safety reserve are subtracted from input. Existing unset profiles preserve their previous behaviour. Presets are explicit numbers, not detected provider capabilities. Apply edits the profile draft; Save persists it. Restore default removes only the selected model override. Config export/import includes overrides.

Local estimation uses three ASCII characters per token, two tokens per BMP non-ASCII character, four per supplementary code point, message overhead and 4,096 tokens per image. These are estimates, not provider tokenizer counts. No tokenizer/model capacity is guessed from model names. Increasing the setting cannot increase provider capacity.

Ordinary requests retain system instructions, current input and a continuous suffix of whole turns; omitted history stays on disk and produces a notice. Story composition uses the selected model's estimated-token budget, including rewritten/historical requests, and keeps existing fixed-memory/unorganized-prose protections and discussion isolation. The shared API layer must never independently trim story jobs. Optional story section caps scale with input capacity; the old 48,000-character policy is only the unset fallback.

The final outgoing payload counts tools and tool results; Responses continuations also account for carried context. Custom output limits are sent as max_tokens for compatible Chat endpoints and max_output_tokens for Responses. See https://developers.openai.com/api/reference/resources/responses/methods/create/ . Automatic stream retries run through the same budget. Requests that cannot preserve mandatory input fail before transmission. Native web-search internal context remains provider-managed; the estimator is not an exact promise of provider acceptance.

Version, main, package name, signing key and story database schema are unchanged.

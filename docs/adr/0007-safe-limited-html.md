# ADR 0007: Persist safe limited HTML alongside canonical plain text

## Status

Accepted for the structured-content stage.

## Context

Article Navigator currently persists only `normalizedText`. That representation is sufficient for deduplication, snippets and basic offline reading, but it destroys headings, lists, emphasis, links, code layout and tables. Reconstructing those semantics later from plain text would be guesswork and would misrepresent historical content.

We need a durable representation that:

- survives extraction -> Inbox -> Save -> immutable DocumentVersion;
- supports formatted reading without executing source code;
- keeps ordinary plain text as the canonical exact-search representation;
- can evolve by an explicit format version;
- treats relative links using the final fetched page URL;
- does not silently load remote resources when a saved document is opened offline;
- can distinguish structural/link changes even when visible plain text is unchanged.

Two approaches were considered: a custom structured block tree and a restricted HTML subset.

## Decision

Persist **safe limited HTML** with format id `safe-html-v1`, alongside `normalizedText`.

The extractor produces both representations from the same decoded document. `normalizedText` remains the search/snippet/compatibility form. `safe-html-v1` is the formatted reading form.

The safe subset contains only passive document semantics needed by the first reader:

- `h1`..`h6`, `p`, `br`, `hr`;
- `ul`, `ol`, `li`;
- `blockquote`;
- `strong`/`b`, `em`/`i`;
- `a[href]` with only absolute HTTP/HTTPS targets;
- `code`, `pre`;
- `table`, `thead`, `tbody`, `tfoot`, `tr`, `th`, `td`, `caption`;
- `figure` and `figcaption` for inert image placeholders/metadata.

Source `script`, `style`, `noscript`, `form`, `input`, `button`, `iframe`, `object`, `embed`, `svg`, `canvas`, event-handler attributes, inline styles and unknown active content are removed. The source page is never executed.

Relative links are resolved during extraction against the final resolved HTTP URL. Unsupported/non-HTTP link schemes are removed from the persisted reading form.

## Rendering

Android renders only the persisted sanitized representation. The reader must disable JavaScript, form/storage capabilities, file/content access and automatic network/resource loading. Link navigation is intercepted and handed to the application only after the user explicitly taps a link.

Legacy rows with no structured representation remain readable through the existing plain-text mode. They are not re-downloaded or rewritten just to manufacture structure.

## Images

`safe-html-v1` does not grant remote image loading. During this first stage an image is converted to inert figure/placeholder metadata with alt/caption text and, when valid, its resolved HTTP/HTTPS source URL stored only as metadata. The reader never requests it automatically.

A later local-resource stage will download selected supported images into app-private storage with explicit byte/count limits, MIME/hash metadata and export/restore support. Missing or corrupt local resources must render a placeholder; the reader must never silently fall back to the remote URL.

This ADR therefore does **not** claim that images are archived in the first structured-content PR.

## Content identity

For new `safe-html-v1` extraction, content identity is the SHA-256 of a domain-separated value containing the format id and deterministic sanitized HTML. This means a changed hyperlink target, table structure, code block or emphasis can be detected even if `normalizedText` remains byte-identical.

Legacy content hashes are not rewritten. Text-only fallback continues to use the canonical normalized plain text hash policy.

## Parser identity

`ContentExtractor` must expose a durable parser version. The pipeline stores the exact version declared by the extractor that produced the two representations. Save copies that persisted version unchanged into `DocumentVersion`. This closes issue #19 before any second extractor is introduced.

## Consequences

Advantages:

- HTML/RSS source semantics survive without inventing a custom serialization grammar;
- tables and inline formatting are naturally expressible;
- the safe subset can be inspected and fixture-tested deterministically;
- old text-only data remains valid;
- exact search stays independent from the rendering format.

Trade-offs:

- the sanitizer and renderer become security-sensitive boundaries and require regression fixtures;
- not every source layout can or should be preserved;
- images are placeholders until bounded local-resource storage is added;
- format evolution requires a new explicit format id rather than silently changing `safe-html-v1` semantics.

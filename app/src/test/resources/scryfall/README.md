# Scryfall regression fixtures

The exact-name lookups below returned these IDs on 2026-09-15 at 17:32:32 UTC; `CardFixturePlanningTest` deserializes the committed projections through generated Moshi adapters before checking the printed fields.

| Fixture | Exact-name query | Response ID |
| --- | --- | --- |
| `jace.json` | `Jace, the Mind Sculptor` | `c8817585-0d32-4d56-9142-0d29512e86a9` |
| `fire-ice.json` | `Fire // Ice` | `18303862-4726-4136-814f-157aa7006579` |
| `invasion-zendikar.json` | `Invasion of Zendikar` | `8fed056f-a8f5-41ec-a7d2-a80a238872d1` |
| `bonecrusher-giant.json` | `Bonecrusher Giant` | `b5b71cd2-de35-451f-b16e-2e3936169407` |

Use this public lookup pattern when deliberately refreshing a fixture, substituting the exact-name query from the table:

```bash
curl --fail --silent --show-error --get https://api.scryfall.com/cards/named \
  --data-urlencode 'exact=Fire // Ice' \
  -H 'User-Agent: SnapstoneReview/1.0' -H 'Accept: application/json'
```

Retain response `id`, `name`, `mana_cost`, `type_line`, `oracle_text`, `power`, `toughness`, `loyalty`, `defense`, `layout`, `image_uris`, and `card_faces` when present. Apply the same field selection to each face; retain only `art_crop`, `normal`, and `large` within image URIs. Preserve missing fields as missing and blank fields as blank, so fixtures keep the distinction exercised by the planner tests. Omit unrelated volatile pricing and inventory fields to avoid irrelevant fixture churn. Do not fetch these URLs during tests.

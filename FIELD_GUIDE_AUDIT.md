# Field-guide audit

## Problems found

1. `RangeMap.kt` did not map a range. It tinted the same whole-country vector for
   every species.
2. The bundled `us_map.xml` contained only part of the boundary and closed the
   path with a straight diagonal from the Pacific Northwest toward Florida.
3. The database had only two range values across all 66 species: 56 `North
   America` and 10 `Western North America`.
4. All 66 `photoUrl` values were generated from presumed Wikimedia filenames.
   They were not verified, and the app did not use them anyway.
5. The runtime photo resolver preferred full-resolution Wikipedia originals,
   had no source/license display, and could silently leave the placeholder when
   loading failed.
6. All 66 species blurbs were boilerplate rather than field-guide content.
7. The uploaded archive contained no trained `.tflite`, `labels.txt`, or
   `model_meta.json`, so its trained model could not be inspected or activated.
8. Live PCM was not cleared at the start of a new recording and trimming the
   rolling buffer repeatedly removed element zero, which was inefficient.

## Changes made

- Correct lower-48 outline generated from a public geographic polygon.
- Exact-taxon iNaturalist photo lookup with medium images, credit and license.
- Wikipedia thumbnail fallback and explicit offline/error fallback.
- Exact-taxon, research-grade occurrence sample plotted as map dots.
- Honest map labeling and no fabricated full-country range fill.
- Legacy guessed photo URLs removed from `species.json`.
- Boilerplate blurb hidden behind a family/habitat summary.
- Home-screen model-backend status added.
- Microphone PCM cleared per session and buffer trimming made efficient.

## Still needing source material

- The actual exported model and labels must be supplied to validate tensor
  dimensions, preprocessing parity and real-world accuracy.
- Expert-reviewed species range descriptions and field-guide prose are still
  needed; occurrence dots are useful evidence but are not authoritative range
  polygons.

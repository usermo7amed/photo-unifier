# Photo Unifier on-device classifier plan

## Current state

The Android app currently uses ML Kit's bundled general image-labeling model. Its labels are mapped to five processing routes: document, poster, interior, exterior, and general. This model was not trained specifically for these classes, so its labels must be treated as weak evidence rather than a document detector.

## Phase 1 — safer routing (implemented in this branch)

- Require a class-specific label with a minimum confidence.
- Require enough accumulated class evidence and a margin over the second-best class.
- Route generic, weak, invalid, or conflicting evidence to GENERAL.
- Keep the existing offline ML Kit model and processing API; do not add model runtime size until a custom model artifact exists.
- Cover the decision policy with JVM unit tests.

## Phase 2 — train a task-specific model

Train a compact MobileNetV3-Small image classifier and export a quantized TensorFlow Lite model. The five mutually exclusive labels are:

- `document`: photographed or scanned pages, forms, receipts, notes, books/pages
- `poster`: posters, flyers, ads, menus, printed signage
- `interior`: indoor rooms and indoor architecture
- `exterior`: outdoor scenes, streets, landscapes, buildings in outdoor context
- `general`: portraits, pets, food, products, art, and images that do not fit the other classes

Dataset requirements:
- Collect consented/licensed images and split by source/photographer, not just random near-duplicate frames.
- Include hard negatives: text on screens, bookshelves, windows, framed art, buildings, indoor plants, and posters photographed in rooms.
- Balance classes, lighting, rotation, perspective, blur, and image quality.
- Keep a held-out real-phone test set. Report per-class precision/recall and confusion matrix; especially measure false DOCUMENT/POSTER predictions because they can trigger perspective correction.
- Quantize only after validating accuracy against the float model.

## Phase 3 — Android integration gate

Only add ML Kit custom image-labeling dependency and the model asset after a real, metadata-compatible `.tflite` file has been trained and checked in. Confirm its input shape, RGB normalization, label order, metadata, size, and license. Preserve the general ML Kit classifier as a fallback when the custom model is missing, fails to load, or has low confidence.

## Phase 4 — evaluate the end-to-end processing

Test classification and the selected image pipeline together on real examples. Measure:
- per-class classification precision/recall;
- document corner detection success and false crop rate;
- visual quality before/after, including paper shadow removal and poster color retention;
- cold-start latency, per-image latency, peak memory, APK size, and offline behavior.

Do not claim a custom classifier is integrated until the trained model artifact and Android inference path are both present and the build/tests pass.

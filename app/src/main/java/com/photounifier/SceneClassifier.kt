package com.photounifier

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Locale

/** The processing route selected for one image. */
enum class ImageKind(val title: String, val labelRes: Int) {
    DOCUMENT("مستند", R.string.kind_document),
    POSTER("بوستر", R.string.kind_poster),
    INTERIOR("مكان داخلي", R.string.kind_interior),
    EXTERIOR("مكان خارجي", R.string.kind_exterior),
    GENERAL("صورة عامة", R.string.kind_general)
}

internal data class LabelSignal(val text: String, val confidence: Float)

data class SceneClassification(
    val kind: ImageKind,
    /** A ranking score derived from matching model labels, not a calibrated probability. */
    val score: Float,
    val matchedLabels: List<String>
)

/**
 * Uses ML Kit's bundled, on-device general image-labeling model. The general model's
 * labels are only evidence, not a reliable document detector. Classification therefore
 * requires class-specific evidence and a margin over the runner-up. Ambiguous images
 * take the non-destructive GENERAL route.
 *
 * A task-trained TFLite model can be added later without changing the repository's
 * processing contract; the custom model must be trained and evaluated for our five
 * output classes before it is shipped.
 */
class SceneClassifier : AutoCloseable {
    private val labeler: ImageLabeler =
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    fun classify(bitmap: Bitmap): SceneClassification {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = Tasks.await(labeler.process(image))
            classifyLabels(labels.map { LabelSignal(it.text, it.confidence) })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            SceneClassification(ImageKind.GENERAL, 0f, emptyList())
        } catch (_: Exception) {
            // Classification must not make a readable image unprocessable.
            SceneClassification(ImageKind.GENERAL, 0f, emptyList())
        }
    }

    override fun close() = labeler.close()

    companion object {
        private const val MIN_CLASS_SCORE = 0.42
        private const val MIN_WINNER_MARGIN = 0.16
        private const val MIN_SPECIFIC_LABEL_CONFIDENCE = 0.42f

        private val keywords: Map<ImageKind, List<Pair<String, Float>>> = mapOf(
            ImageKind.DOCUMENT to listOf(
                "document" to 1.5f,
                "receipt" to 1.25f,
                "invoice" to 1.2f,
                "form" to 1.0f,
                "paper" to 0.8f,
                "worksheet" to 1.0f,
                "handwriting" to 0.7f,
                "printed page" to 0.8f,
                "text" to 0.22f,
                "book" to 0.3f,
                "notebook" to 0.35f
            ),
            ImageKind.POSTER to listOf(
                "poster" to 1.6f,
                "billboard" to 1.25f,
                "banner" to 1.1f,
                "advertisement" to 1.15f,
                "flyer" to 1.0f,
                "typography" to 0.7f,
                "sign" to 0.55f,
                "text" to 0.12f
            ),
            ImageKind.INTERIOR to listOf(
                "interior design" to 1.35f,
                "interior" to 1.25f,
                "indoor" to 1.2f,
                "living room" to 1.1f,
                "bedroom" to 1.0f,
                "kitchen" to 1.0f,
                "bathroom" to 0.85f,
                "room" to 0.8f,
                "furniture" to 0.7f,
                "office" to 0.65f,
                "ceiling" to 0.5f,
                "desk" to 0.4f,
                "hallway" to 0.7f,
                "apartment" to 0.6f
            ),
            ImageKind.EXTERIOR to listOf(
                "outdoor" to 1.25f,
                "landscape" to 0.85f,
                "street" to 0.85f,
                "road" to 0.7f,
                "beach" to 0.75f,
                "mountain" to 0.7f,
                "forest" to 0.65f,
                "garden" to 0.65f,
                "sky" to 0.5f,
                "nature" to 0.5f,
                "tree" to 0.3f,
                "city" to 0.6f,
                "building" to 0.35f,
                "house" to 0.3f,
                "architecture" to 0.25f
            )
        )

        // Broad labels such as "text", "room", "sky", or "building" may support a
        // decision but cannot start it on their own.
        private val specificKeywords: Map<ImageKind, Set<String>> = mapOf(
            ImageKind.DOCUMENT to setOf(
                "document", "receipt", "invoice", "form", "worksheet", "printed page"
            ),
            ImageKind.POSTER to setOf(
                "poster", "billboard", "banner", "advertisement", "flyer"
            ),
            ImageKind.INTERIOR to setOf(
                "interior design", "interior", "indoor", "living room", "bedroom",
                "kitchen", "bathroom", "hallway", "apartment"
            ),
            ImageKind.EXTERIOR to setOf(
                "outdoor", "landscape", "street", "road", "beach", "mountain",
                "forest", "garden", "nature"
            )
        )

        internal fun classifyLabels(labels: List<LabelSignal>): SceneClassification {
            if (labels.isEmpty()) return SceneClassification(ImageKind.GENERAL, 0f, emptyList())

            val normalized = labels.mapNotNull { signal ->
                val text = signal.text.lowercase(Locale.ROOT).trim()
                if (text.isEmpty() || !signal.confidence.isFinite()) null
                else signal.copy(confidence = signal.confidence.coerceIn(0f, 1f)) to text
            }
            if (normalized.isEmpty()) {
                return SceneClassification(ImageKind.GENERAL, 0f, emptyList())
            }

            val scores = keywords.mapValues { (_, entries) ->
                normalized.sumOf { (signal, text) ->
                    val weight = entries.asSequence()
                        .filter { (word, _) -> matches(text, word) }
                        .maxOfOrNull { it.second } ?: 0f
                    (weight * signal.confidence).toDouble()
                }
            }
            val ranked = scores.entries.sortedByDescending { it.value }
            val winner = ranked.first()
            val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
            val hasSpecificEvidence = normalized.any { (signal, text) ->
                signal.confidence >= MIN_SPECIFIC_LABEL_CONFIDENCE &&
                    specificKeywords[winner.key].orEmpty().any { matches(text, it) }
            }
            val winnerLabels = normalized.filter { (signal, text) ->
                keywords[winner.key].orEmpty().any { (word, weight) ->
                    weight >= 0.7f && matches(text, word) && signal.confidence > 0f
                }
            }.map { it.first.text }.distinct()

            // Never choose a specialized image pipeline from generic or conflicting labels.
            if (
                winner.value < MIN_CLASS_SCORE ||
                winner.value - runnerUp < MIN_WINNER_MARGIN ||
                !hasSpecificEvidence
            ) {
                return SceneClassification(
                    ImageKind.GENERAL,
                    0f,
                    normalized.map { it.first.text }.distinct()
                )
            }

            return SceneClassification(
                kind = winner.key,
                score = winner.value.coerceAtMost(1.0).toFloat(),
                matchedLabels = winnerLabels
            )
        }

        private fun matches(label: String, keyword: String): Boolean {
            if (keyword.contains(' ')) return label.contains(keyword)
            return label.split(Regex("[^a-z0-9]+"))
                .any { token -> token == keyword }
        }
    }
}

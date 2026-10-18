package com.campusai.core.ai

import org.json.JSONArray
import org.json.JSONObject

/** Bounds optional evidence as whole JSON entries, with explicit coverage counts. */
internal object AiContextBudget {
    // The wire limit stays 12,000. Leave room for the separately consented typed health summary.
    const val CLOUD_OPTIONAL_TARGET = 10_000

    fun putItems(parent: JSONObject, key: String, items: Sequence<JSONObject>, maxItems: Int, maxChars: Int): JSONObject {
        val included = JSONArray()
        var chars = 2 // []
        var total = 0
        var full = false
        items.forEach { item ->
            total++
            if (!full) {
                val size = item.toString().length + if (included.length() == 0) 0 else 1
                if (included.length() >= maxItems || chars + size > maxChars) full = true
                else { included.put(item); chars += size }
            }
        }
        parent.put(key, included)
        counts(parent, key, total, included.length())
        return parent
    }

    fun fitOptionalDetails(context: JSONObject): JSONObject {
        // Never remove task facts, action/confirmation parameters, unknown fields or health consent.
        val personal = context.optJSONObject("personalContext") ?: return context
        val courses = personal.optJSONObject("courses")
        val optional = listOf(
            courses to "computedConflicts",
            personal to "selectedPublicPosts",
            personal to "ownPosts",
            personal.optJSONObject("learning") to "recentRelevantEntries",
            courses to "items",
        )
        for ((parent, key) in optional) {
            val items = parent?.optJSONArray(key) ?: continue
            val total = maxOf(parent.optInt("${key}TotalCount", items.length()), items.length())
            while (items.length() > 0 && context.toString().length > CLOUD_OPTIONAL_TARGET) {
                items.remove(items.length() - 1)
                counts(parent, key, total, items.length())
            }
        }
        // If mandatory/unknown evidence still exceeds the wire limit, the caller rejects it.
        return context
    }

    private fun counts(parent: JSONObject, key: String, total: Int, included: Int) {
        parent.put("${key}TotalCount", total)
            .put("${key}IncludedCount", included)
            .put("${key}OmittedCount", total - included)
    }
}

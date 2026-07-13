package com.sameerakhtari.riddle.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class InkTool {
    PEN,
    ERASER,
}

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timeDeltaMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("pressure", pressure.toDouble())
        .put("timeDeltaMs", timeDeltaMs)

    companion object {
        fun fromJson(json: JSONObject): StrokePoint = StrokePoint(
            x = json.optDouble("x", 0.0).toFloat(),
            y = json.optDouble("y", 0.0).toFloat(),
            pressure = json.optDouble("pressure", 1.0).toFloat(),
            timeDeltaMs = json.optLong("timeDeltaMs", 0L),
        )
    }
}

data class Stroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: InkTool,
    val baseWidth: Float,
    val points: MutableList<StrokePoint> = mutableListOf(),
) {
    fun deepCopy(): Stroke = copy(points = points.toMutableList())

    fun toJson(): JSONObject {
        val jsonPoints = JSONArray()
        points.forEach { jsonPoints.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("tool", tool.name)
            .put("baseWidth", baseWidth.toDouble())
            .put("points", jsonPoints)
    }

    companion object {
        fun fromJson(json: JSONObject): Stroke {
            val points = mutableListOf<StrokePoint>()
            val array = json.optJSONArray("points") ?: JSONArray()
            for (index in 0 until array.length()) {
                points += StrokePoint.fromJson(array.getJSONObject(index))
            }
            return Stroke(
                id = json.optString("id", UUID.randomUUID().toString()),
                tool = runCatching {
                    InkTool.valueOf(json.optString("tool", InkTool.PEN.name))
                }.getOrDefault(InkTool.PEN),
                baseWidth = json.optDouble("baseWidth", 4.0).toFloat(),
                points = points,
            )
        }
    }
}

data class DiaryPage(
    val id: String,
    val sessionId: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val imageFileName: String,
    val strokes: List<Stroke>,
    val transcript: String = "",
    val reply: String = "",
    val error: String = "",
) {
    fun toJson(): JSONObject {
        val jsonStrokes = JSONArray()
        strokes.forEach { jsonStrokes.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("imageFileName", imageFileName)
            .put("strokes", jsonStrokes)
            .put("transcript", transcript)
            .put("reply", reply)
            .put("error", error)
    }

    companion object {
        fun fromJson(json: JSONObject): DiaryPage {
            val strokes = mutableListOf<Stroke>()
            val array = json.optJSONArray("strokes") ?: JSONArray()
            for (index in 0 until array.length()) {
                strokes += Stroke.fromJson(array.getJSONObject(index))
            }
            return DiaryPage(
                id = json.getString("id"),
                sessionId = json.optString("sessionId"),
                createdAt = json.optLong("createdAt"),
                updatedAt = json.optLong("updatedAt"),
                imageFileName = json.optString("imageFileName"),
                strokes = strokes,
                transcript = json.optString("transcript"),
                reply = json.optString("reply"),
                error = json.optString("error"),
            )
        }
    }
}

data class DiarySession(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String = "New conversation",
    val summary: String = "",
    val pageIds: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        val ids = JSONArray()
        pageIds.forEach(ids::put)
        return JSONObject()
            .put("id", id)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("title", title)
            .put("summary", summary)
            .put("pageIds", ids)
    }

    companion object {
        fun new(): DiarySession {
            val now = System.currentTimeMillis()
            return DiarySession(
                id = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
            )
        }

        fun fromJson(json: JSONObject): DiarySession {
            val ids = mutableListOf<String>()
            val array = json.optJSONArray("pageIds") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(ids::add)
            }
            return DiarySession(
                id = json.optString("id", UUID.randomUUID().toString()),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                title = json.optString("title", "New conversation"),
                summary = json.optString("summary"),
                pageIds = ids,
            )
        }
    }
}

data class DraftState(
    val sessionId: String,
    val strokes: List<Stroke>,
)

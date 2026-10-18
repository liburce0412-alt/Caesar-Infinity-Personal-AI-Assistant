package com.campusai.core.appfunctions

import com.campusai.core.time.CaesarTime

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.TimeRecordEntity
import com.campusai.features.community.CampusRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Low-risk platform adapter. Caesar's in-process runtime does not depend on this service. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "CaesarExternalAppFunctionService",
    appFunctionXmlFileName = "caesar_external_app_functions",
)
abstract class CaesarAppFunctionEntryPoint : AppFunctionService() {

    /** Returns today's local time records as a size-limited JSON array. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getTodayTimeRecords(context: AppFunctionContext): String = withContext(Dispatchers.IO) {
        val zone = CaesarTime.zone
        val today = Instant.now().atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = CampusDatabase.getDatabase(context.context).campusDao()
            .getAllTimeRecordsFlow("local_user", true).first()
            .filter { it.startTime in start until end }
            .take(50)
        JSONArray(rows.map { JSONObject().put("id", it.id).put("title", it.title).put("category", it.category).put("startTime", it.startTime).put("endTime", it.endTime).put("durationMinutes", it.durationMinutes) }).toString()
    }

    /** Returns the local course schedule as a size-limited JSON array. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getCourseSchedule(context: AppFunctionContext): String = withContext(Dispatchers.IO) {
        val rows = CampusDatabase.getDatabase(context.context).campusDao()
            .getCourseSchedulesFlow("local_user", true).first().take(100)
        JSONArray(rows.map { JSONObject().put("id", it.id).put("name", it.name).put("weekday", it.weekday).put("startMinute", it.startMinute).put("endMinute", it.endMinute).put("location", it.location).put("teacher", it.teacher).put("weeks", it.weeks) }).toString()
    }

    /** Returns the latest owner-approved tree-hollow announcements. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getTreeHollowAnnouncements(context: AppFunctionContext): String = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE") val applicationContext = context.context
        CampusRepository().loadAnnouncements().fold(
            onSuccess = { rows -> JSONArray(rows.take(3).map { JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("publishAt", it.publishAt) }).toString() },
            onFailure = { error -> JSONObject().put("error", error.message ?: "公告读取失败").toString() },
        )
    }

    /** Creates a reversible local time record ending now, with duration between 1 and 1440 minutes. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createLocalTimeRecord(
        context: AppFunctionContext,
        title: String,
        category: String,
        durationMinutes: Long,
        remark: String,
    ): String = withContext(Dispatchers.IO) {
        require(title.trim().length in 1..120)
        require(category.trim().length in 1..40)
        require(durationMinutes in 1..1_440)
        require(remark.length <= 500)
        val end = System.currentTimeMillis()
        val id = CampusDatabase.getDatabase(context.context).campusDao().insertTimeRecord(
            TimeRecordEntity(
                title = title.trim(),
                category = category.trim(),
                startTime = end - durationMinutes * 60_000L,
                endTime = end,
                durationMinutes = durationMinutes,
                remark = remark.trim(),
                userId = "local_user",
            ),
        )
        JSONObject().put("id", id).put("created", true).put("undoAvailable", true).toString()
    }
}

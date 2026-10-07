package com.campusai.features.schedule

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.campusai.R
import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseIslandExpandedCardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val elapsed = 12_000L
    private val item = CourseOccurrence(
        CourseSchedule(8, "Java 程序设计", 3, 480, 580, "教学楼[S402]", periodStart = 3, periodEnd = 4, sourceHash = "a"),
        LocalDate.of(2026, 10, 7), now + 60_000, now + 180_000,
    )

    private fun notification(): Notification = Notification.Builder(context, "test")
        .setSmallIcon(R.drawable.ic_course_notification)
        .setContentIntent(PendingIntent.getActivity(context, 1,
            Intent("campusai.test.OPEN").putExtra("owner", "account-a").putExtra("course_id", item.course.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setDeleteIntent(PendingIntent.getBroadcast(context, 2,
            Intent("campusai.test.DISMISS").putExtra("owner", "account-a").putExtra("key", item.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    /** Exercise parceling and the RemoteViews inflater whitelist as a remote host would. */
    private fun apply(key: String = CourseIslandExpandedCard.EXPANDED, at: Long = now): View {
        val bundle = CourseIslandExpandedCard.extras(context, notification(), item, at, elapsed)
        val parcel = Parcel.obtain()
        val copy = try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        val remote = copy.getParcelable(key, RemoteViews::class.java)!!
        return remote.apply(context, FrameLayout(context))
    }

    @Test fun `all remote surfaces parcel and inflate with readable theme and native countdown`() {
        for (key in listOf(CourseIslandExpandedCard.SHADE, CourseIslandExpandedCard.SHADE_NIGHT, CourseIslandExpandedCard.EXPANDED)) {
            val view = apply(key)
            assertEquals(item.course.name, view.findViewById<TextView>(R.id.course_island_name).text.toString())
            assertEquals("S402", view.findViewById<TextView>(R.id.course_island_room).text.toString())
            val clock = view.findViewById<Chronometer>(R.id.course_island_timer)
            assertTrue(clock.isCountDown)
            assertEquals(elapsed + item.start - now, clock.base)
            val titleColor = view.findViewById<TextView>(R.id.course_island_name).currentTextColor
            assertEquals(if (key == CourseIslandExpandedCard.SHADE) 0xff182537.toInt() else 0xfff1f3ff.toInt(), titleColor)
            measure(view)
            capture(view, "course-island-${key.substringAfterLast('.')}.png")
        }
    }

    @Test fun `class start switches deadline to end without wall clock as chronometer base`() {
        val view = apply(at = item.start)
        assertEquals("距下课", view.findViewById<TextView>(R.id.course_island_timer_label).text.toString())
        assertEquals(elapsed + item.end - item.start, view.findViewById<Chronometer>(R.id.course_island_timer).base)
    }

    @Test fun `native buttons retain the owning course and account pending intents`() {
        val view = apply()
        assertTrue(view.findViewById<View>(R.id.course_island_open).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        val open = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("campusai.test.OPEN", open.action)
        assertEquals("account-a", open.getStringExtra("owner"))
        assertEquals(item.course.id, open.getIntExtra("course_id", -1))
        assertTrue(view.findViewById<View>(R.id.course_island_dismiss).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        val dismiss = shadowOf(context as android.app.Application).broadcastIntents.last { it.action == "campusai.test.DISMISS" }
        assertEquals("account-a", dismiss.getStringExtra("owner"))
        assertEquals(item.key, dismiss.getStringExtra("key"))
    }

    @Test fun `flow is an actual animatable drawable only in the expanded native view`() {
        val view = apply()
        val flow = view.findViewById<ProgressBar>(R.id.course_island_flow)
        assertTrue(flow.isIndeterminate)
        assertEquals(View.VISIBLE, flow.visibility)
        val animated = flow.indeterminateDrawable as AnimatedVectorDrawable
        measure(view)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        animated.start()
        view.draw(Canvas(bitmap))
        assertTrue(animated.isRunning)
        animated.stop()
        bitmap.recycle()
        assertEquals(View.GONE, apply(CourseIslandExpandedCard.SHADE).findViewById<View>(R.id.course_island_flow).visibility)
    }

    private fun measure(view: View) {
        val density = context.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun capture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = File("../../../artifacts", name)
            output.parentFile.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}

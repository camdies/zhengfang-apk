package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Shared transport boundary for the four grade APIs.
 *
 * The repository is the only component that consumes each response body.  It
 * forwards a value-free tri-state classification with the body so both legacy
 * UI entry points make identical expiry/unknown decisions.
 */
class AcademicRepository(
    private val api: CourseApiClient = CourseApiClient.getInstance()
) {
    fun fetchGrades(
        school: SchoolConfig,
        semester: String,
        context: SessionRequestContext,
        callback: AcademicResponseCallback
    ) = enqueue(context, callback) { responseCallback ->
        api.fetchGrades(school, semester, context, responseCallback)
    }

    fun fetchGradeDetails(
        school: SchoolConfig,
        semester: String,
        context: SessionRequestContext,
        callback: AcademicResponseCallback
    ) = enqueue(context, callback) { responseCallback ->
        api.fetchGradeDetails(school, semester, context, responseCallback)
    }

    fun fetchOverallGradesIndex(
        school: SchoolConfig,
        context: SessionRequestContext,
        callback: AcademicResponseCallback
    ) = enqueue(context, callback) { responseCallback ->
        api.fetchOverallGradesIndex(school, context, responseCallback)
    }

    fun fetchOverallGradesData(
        school: SchoolConfig,
        postBody: String,
        context: SessionRequestContext,
        callback: AcademicResponseCallback
    ) = enqueue(context, callback) { responseCallback ->
        api.fetchOverallGradesData(school, postBody, context, responseCallback)
    }

    fun fetchExamSchedule(
        school: SchoolConfig,
        academicYear: String,
        termCode: String,
        context: SessionRequestContext,
        callback: AcademicResponseCallback
    ) = enqueue(context, callback) { responseCallback ->
        api.fetchExamSchedule(school, academicYear, termCode, context, responseCallback)
    }

    private fun enqueue(
        expectedContext: SessionRequestContext,
        callback: AcademicResponseCallback,
        request: (Callback) -> Unit
    ) {
        request(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (e is CourseApiClient.ProtocolNotVerifiedException) {
                    callback.onProtocolNotVerified(expectedContext)
                } else {
                    callback.onFailure(expectedContext, e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { value ->
                    val context = value.request.tag(SessionRequestContext::class.java) ?: expectedContext
                    val body = value.body?.string().orEmpty()
                    val classification = SessionResponseClassifiers.classifyConsumedBody(context, value, body)
                    api.reportSessionClassification(context, classification)
                    callback.onResponse(
                        AcademicResponse(
                            context = context,
                            body = body,
                            classification = classification,
                            httpCode = value.code
                        )
                    )
                }
            }
        })
    }
}

data class AcademicResponse(
    val context: SessionRequestContext,
    val body: String,
    val classification: SessionResponseClassification,
    val httpCode: Int
) {
    val isConfirmedExpired: Boolean
        get() = classification.state == SessionResponseState.CONFIRMED_EXPIRED

    val isCurrent: Boolean
        get() = context.isSnapshotCurrent()
}

interface AcademicResponseCallback {
    fun onResponse(response: AcademicResponse)
    fun onFailure(context: SessionRequestContext, error: IOException)
    fun onProtocolNotVerified(context: SessionRequestContext)
}

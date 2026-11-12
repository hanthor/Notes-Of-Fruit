package com.example.applenotes.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.applenotes.data.AppGraph
import com.example.applenotes.data.DraftScheduler
import com.example.applenotes.log.AppLog
import java.util.concurrent.TimeUnit

private const val TAG = "DraftSync"

/**
 * Saves one unsent draft in the background (issue #13), so an edit outlives
 * the activity and the process: enqueued when the editor pauses or is left
 * with unsaved changes, runs once there is a network, retried with
 * exponential backoff. All logic is in [DraftSyncer].
 */
class DraftSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_DRAFT) ?: return Result.failure()
        val repository = AppGraph.notesRepository(applicationContext)
        return when (val outcome = DraftSyncer(repository).sync(key)) {
            DraftSyncer.Outcome.Done, DraftSyncer.Outcome.Conflict -> Result.success()
            DraftSyncer.Outcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            is DraftSyncer.Outcome.Failed -> {
                // The draft stays in the outbox; the list offers a manual retry.
                AppLog.w(TAG, "giving up on a background save: ${outcome.reason}")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_DRAFT = "draftKey"
        private const val MAX_ATTEMPTS = 12
    }
}

/** [DraftScheduler] on WorkManager: one unique work chain per draft key. */
class WorkManagerDraftScheduler(context: Context) : DraftScheduler {
    private val app = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(app) }

    override fun enqueue(key: String) {
        val request = OneTimeWorkRequestBuilder<DraftSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(DraftSyncWorker.KEY_DRAFT to key))
            .addTag(WORK_TAG)
            .build()
        // APPEND_OR_REPLACE: never two runs for the same note at once; a run
        // already going is followed by this one (which re-reads the draft).
        workManager.enqueueUniqueWork(uniqueName(key), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun cancel(key: String) {
        workManager.cancelUniqueWork(uniqueName(key))
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    private fun uniqueName(key: String) = "draft-sync:$key"

    private companion object {
        const val WORK_TAG = "draft-sync"
    }
}

/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package at.bitfire.davdroid.syncadapter

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.InvalidAccountException
import at.bitfire.davdroid.log.Logger
import at.bitfire.davdroid.settings.AccountSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.logging.Level

abstract class SyncAdapterService: Service() {

    fun syncAdapter() = SyncAdapter(this)

    override fun onBind(intent: Intent?) = syncAdapter().syncAdapterBinder!!

    /**
     * Entry point for the sync adapter framework.
     *
     * Handles incoming sync requests from the sync adapter framework.
     *
     * Although we do not use the sync adapter for syncing anymore, we keep this sole
     * adapter to provide exported services, which allow android system components and calendar,
     * contacts or task apps to sync via DAVx5.
     */
    class SyncAdapter(
        context: Context
    ): AbstractThreadedSyncAdapter(
        context,
        true    // isSyncable shouldn't be -1 because DAVx5 sets it to 0 or 1.
                           // However, if it is -1 by accident, set it to 1 to avoid endless sync loops.
    ) {

        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
            // We seem to have to pass this old SyncFramework extra for an Android 7 workaround
            val upload = extras.containsKey(ContentResolver.SYNC_EXTRAS_UPLOAD)
            Logger.log.info("Sync request via sync framework (upload=$upload)")

            val accountSettings = try {
                AccountSettings(context, account)
            } catch (e: InvalidAccountException) {
                Logger.log.log(Level.WARNING, "Account doesn't exist anymore", e)
                return
            }

            // Should we run the sync at all?
            if (!SyncWorker.wifiConditionsMet(context, accountSettings)) {
                Logger.log.info("Sync conditions not met. Aborting sync framework initiated sync")
                return
            }

            Logger.log.fine("Sync framework now starting SyncWorker")
            val workerName = SyncWorker.enqueue(context, account, authority, upload = upload)

            // Block the onPerformSync method to simulate an ongoing sync
            Logger.log.fine("Blocking sync framework until SyncWorker finishes")

            // Because we are not allowed to observe worker state on a background thread, we can not
            // use it to block the sync adapter. Instead we check periodically whether the sync has
            // finished, putting the thread to sleep in between checks.
            val workManager = WorkManager.getInstance(context)
            val status = workManager.getWorkInfosForUniqueWorkLiveData(workerName)

            var finished = false
            val lock = Object()

            val observer = Observer<List<WorkInfo>> { workInfoList ->
                for (workInfo in workInfoList) {
                    if (workInfo.state.isFinished) {
                        synchronized(lock) {
                            finished = true
                            lock.notify()
                        }
                    }
                }
            }

            runBlocking(Dispatchers.Main) {     // observeForever not allowed in background thread
                status.observeForever(observer)
            }

            synchronized(lock) {
                try {
                    if (!finished)
                        lock.wait(10*60*1000)  // wait max 10 minutes
                } catch (e: InterruptedException) {
                    Logger.log.info("Interrupted while blocking sync framework. Sync may still be running")
                }
            }

            runBlocking(Dispatchers.Main) {
                status.removeObserver(observer)
            }

            Logger.log.info("Returning to sync framework")
        }

        override fun onSecurityException(account: Account, extras: Bundle, authority: String, syncResult: SyncResult) {
            Logger.log.log(Level.WARNING, "Security exception for $account/$authority")
        }

        override fun onSyncCanceled() {
            Logger.log.info("Ignoring sync adapter cancellation")
            super.onSyncCanceled()
        }

        override fun onSyncCanceled(thread: Thread) {
            Logger.log.info("Ignoring sync adapter cancellation")
            super.onSyncCanceled(thread)
        }

    }

}

// exported sync adapter services; we need a separate class for each authority
class AddressBooksSyncAdapterService: SyncAdapterService()
class CalendarsSyncAdapterService: SyncAdapterService()
class ContactsSyncAdapterService: SyncAdapterService()
class JtxSyncAdapterService: SyncAdapterService()
class OpenTasksSyncAdapterService: SyncAdapterService()
class TasksOrgSyncAdapterService: SyncAdapterService()

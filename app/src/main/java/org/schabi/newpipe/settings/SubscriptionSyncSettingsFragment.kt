package org.schabi.newpipe.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.sync.subscriptions.DiscoveredSubscriptionPeer
import org.schabi.newpipe.sync.subscriptions.SubscriptionFolder
import org.schabi.newpipe.sync.subscriptions.SubscriptionLan
import org.schabi.newpipe.sync.subscriptions.SubscriptionSync
import org.schabi.newpipe.sync.subscriptions.SubscriptionTls

class SubscriptionSyncSettingsFragment : PreferenceFragmentCompat() {
    private var lan: SubscriptionLan? = null
    private var lanStartup: Job? = null
    private var pairingDialog: AlertDialog? = null
    private var devices = emptyList<DiscoveredSubscriptionPeer>()
    private var busy = false
    private lateinit var status: Preference
    private lateinit var enable: Preference
    private lateinit var lanCategory: PreferenceCategory
    private lateinit var nasCategory: PreferenceCategory
    private lateinit var allowPairing: Preference
    private lateinit var nearby: Preference
    private lateinit var folder: Preference
    private lateinit var folderSync: Preference
    private lateinit var purge: Preference

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        work {
            val context = requireContext().applicationContext
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            check(data.flags and flags == flags) { getString(R.string.subscription_sync_folder_access) }
            withContext(Dispatchers.IO) {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                SubscriptionSync.setDirectory(context, uri)
            }
            getString(R.string.subscription_sync_folder_ready)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        screen.setTitle(R.string.subscription_sync_title)
        preferenceScreen = screen
        enable = action(R.string.subscription_sync_enable) {
            work {
                val context = requireContext().applicationContext
                withContext(Dispatchers.IO) { SubscriptionSync.enable(context) }
                startLan()
                getString(R.string.subscription_sync_ready)
            }
        }.also(screen::addPreference)
        status = Preference(requireContext()).apply {
            setTitle(R.string.subscription_sync_status)
            setSummary(R.string.subscription_sync_status_waiting)
            isSelectable = false
        }.also(screen::addPreference)
        lanCategory = PreferenceCategory(requireContext()).apply {
            setTitle(R.string.subscription_sync_lan)
        }.also(screen::addPreference)
        allowPairing = action(R.string.subscription_sync_allow_pairing) { lan?.allowPairing() }
            .also(lanCategory::addPreference)
        nearby = action(R.string.subscription_sync_nearby, ::showNearby).also(lanCategory::addPreference)
        nasCategory = PreferenceCategory(requireContext()).apply {
            setTitle(R.string.subscription_sync_nas)
        }.also(screen::addPreference)
        folder = action(R.string.subscription_sync_folder) {
            try {
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                ))
            } catch (_: android.content.ActivityNotFoundException) {
                status.setSummary(R.string.subscription_sync_picker_missing)
            }
        }.also(nasCategory::addPreference)
        folderSync = action(R.string.subscription_sync_folder_now, ::syncFolder).also(nasCategory::addPreference)
        purge = action(R.string.subscription_sync_purge, ::confirmPurge).also(screen::addPreference)
        purge.setSummary(R.string.subscription_sync_purge_summary)
        render()
    }

    override fun onStart() {
        super.onStart()
        render()
        startLan()
    }

    override fun onStop() {
        stopLan()
        super.onStop()
    }

    private fun action(title: Int, click: () -> Unit) = Preference(requireContext()).apply {
        setTitle(title)
        setOnPreferenceClickListener { click(); true }
    }

    private fun render() {
        val context = requireContext().applicationContext
        val enabled = SubscriptionSync.isEnabled(context)
        enable.isVisible = !enabled
        enable.isEnabled = !busy
        lanCategory.isVisible = enabled
        nasCategory.isVisible = enabled
        purge.isEnabled = !busy
        allowPairing.isEnabled = lan != null
        nearby.isEnabled = devices.isNotEmpty()
        nearby.summary = resources.getQuantityString(
            R.plurals.subscription_sync_nearby_count, devices.size, devices.size
        )
        val directory = SubscriptionSync.directory(context)
        folder.summary = directory?.lastPathSegment ?: getString(R.string.subscription_sync_folder_unset)
        folder.isEnabled = !busy
        folderSync.isEnabled = directory != null && !busy
    }

    private fun startLan() {
        val context = requireContext().applicationContext
        if (!SubscriptionSync.isEnabled(context) || lan != null || lanStartup?.isActive == true) return
        lanStartup = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tls = withContext(Dispatchers.IO) { SubscriptionTls.load() }
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val session = SubscriptionSync.session(context)
                val connection = SubscriptionLan(context, session, tls,
                    onDevices = { devices = it; render() },
                    onStatus = { status.summary = it },
                    onPairing = ::confirmPair
                )
                lan = connection
                connection.start()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                stopLan()
                status.summary = failure.message ?: getString(R.string.subscription_sync_failed)
            }
            render()
        }
    }

    private fun stopLan() {
        lanStartup?.cancel()
        lanStartup = null
        lan?.close()
        lan = null
        devices = emptyList()
        pairingDialog?.dismiss()
        pairingDialog = null
    }

    private fun showNearby() {
        val nearbyDevices = devices
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.subscription_sync_nearby)
            .setItems(nearbyDevices.map { "${it.name} · ${it.id.take(8)}" }.toTypedArray()) { _, index ->
                showPeer(nearbyDevices[index])
            }
            .show()
    }

    private fun showPeer(device: DiscoveredSubscriptionPeer) {
        val context = requireContext().applicationContext
        val peer = SubscriptionSync.peer(context, device.id)
        val dialog = AlertDialog.Builder(requireContext()).setTitle(device.name)
        if (peer == null) {
            dialog.setMessage(R.string.subscription_sync_pair_help)
                .setPositiveButton(R.string.subscription_sync_pair) { _, _ -> connect(device) }
                .setNegativeButton(android.R.string.cancel, null)
        } else {
            val directions = booleanArrayOf(peer.send, peer.receive)
            dialog.setMultiChoiceItems(
                arrayOf(getString(R.string.subscription_sync_send), getString(R.string.subscription_sync_receive)),
                directions
            ) { _, index, checked -> directions[index] = checked }
                .setPositiveButton(R.string.subscription_sync_now) { _, _ ->
                    work {
                        withContext(Dispatchers.IO) {
                            SubscriptionSync.savePeer(context, SubscriptionSync.session(context),
                                peer.copy(send = directions[0], receive = directions[1]))
                        }
                        connect(device)
                        null
                    }
                }
                .setNeutralButton(R.string.subscription_sync_forget) { _, _ ->
                    work {
                        withContext(Dispatchers.IO) {
                            SubscriptionSync.forgetPeer(context, SubscriptionSync.session(context), peer.id)
                        }
                        getString(R.string.subscription_sync_forgotten)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
        }
        dialog.show()
    }

    private fun connect(device: DiscoveredSubscriptionPeer) {
        val connection = lan
        if (connection == null) {
            status.setSummary(R.string.subscription_sync_lan_unavailable)
            return
        }
        runCatching { connection.connect(device) }.onFailure {
            status.summary = it.message ?: getString(R.string.subscription_sync_failed)
        }
    }

    private fun confirmPair(code: String, decide: (Boolean) -> Unit) {
        if (pairingDialog != null) {
            decide(false)
            return
        }
        pairingDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.subscription_sync_pair)
            .setMessage(getString(R.string.subscription_sync_compare, code))
            .setPositiveButton(R.string.subscription_sync_match) { _, _ -> decide(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> decide(false) }
            .setOnCancelListener { decide(false) }
            .setOnDismissListener { pairingDialog = null }
            .show()
    }

    private fun syncFolder() = work {
        val context = requireContext().applicationContext
        val uri = SubscriptionSync.directory(context) ?: return@work null
        val result = withContext(Dispatchers.IO) {
            SubscriptionFolder.sync(context, SubscriptionSync.session(context), uri)
        }
        getString(R.string.subscription_sync_nas_result, result.applied, result.skipped, result.unreadable)
    }

    private fun confirmPurge() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val count = withContext(Dispatchers.IO) { SubscriptionSync.purgeCount(context) }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.subscription_sync_purge)
                .setMessage(resources.getQuantityString(R.plurals.subscription_sync_purge_confirm, count, count))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.subscription_sync_purge) { _, _ ->
                    stopLan()
                    work {
                        val removed = withContext(Dispatchers.IO) { SubscriptionSync.purge(context) }
                        resources.getQuantityString(R.plurals.subscription_sync_purged, removed, removed)
                    }
                }
                .show()
        }
    }

    private fun work(block: suspend () -> String?) {
        if (busy) return
        busy = true
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()?.let { status.summary = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                status.summary = failure.message ?: getString(R.string.subscription_sync_failed)
            } finally {
                busy = false
                if (isAdded) render()
            }
        }
    }
}

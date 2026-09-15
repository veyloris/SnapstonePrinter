package com.example.snapstoneprinter.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snapstoneprinter.data.print.ChosenComponentReceiver
import com.example.snapstoneprinter.data.print.DispatchToken
import com.example.snapstoneprinter.data.print.PrintJobState

@Composable
internal fun PrintDispatchHost(
    viewModel: ProxyGeneratorViewModel,
    registry: ActivityResultRegistry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val token = when (val job = state.printJob) {
        is PrintJobState.Ready -> job.token
        is PrintJobState.Launched -> job.token
        is PrintJobState.Stopping -> job.token
        else -> null
    }
    if (token != null) {
        DisposableEffect(registry, viewModel, token) {
            val launcher = registry.register(
                "snapstone-print:${token.jobId}:${token.index}",
                ActivityResultContracts.StartActivityForResult()
            ) { viewModel.onPrintReturned(token) }
            // Registration may synchronously deliver a queued result; claim rechecks ownership.
            val request = viewModel.claimPrintLaunch(token)
            if (request != null) {
                val send = buildPrintSendIntent(Uri.parse(request.uri))
                val target = viewModel.uiState.value.printerTarget
                var useChooser = target == null
                if (target != null) {
                    try {
                        launcher.launch(Intent(send).setComponent(target.component))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.forgetPrinterTarget()
                        useChooser = true
                    } catch (_: SecurityException) {
                        viewModel.forgetPrinterTarget()
                        useChooser = true
                    } catch (_: Exception) {
                        viewModel.onPrintLaunchFailed(token, "No app could open this slip.")
                    }
                }
                if (useChooser) {
                    try {
                        launcher.launch(Intent.createChooser(send,
                            "Print slip ${token.index + 1} of ${request.total} — ${request.label}",
                            chosenComponentSender(context, token)))
                    } catch (_: Exception) {
                        viewModel.onPrintLaunchFailed(token, "No app could open this slip.")
                    }
                }
            }
            onDispose { launcher.unregister() }
        }
    }
}

private fun buildPrintSendIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/png"
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newRawUri("Proxy slip", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun chosenComponentSender(context: Context, token: DispatchToken) = PendingIntent.getBroadcast(
    context, 0,
    Intent(context, ChosenComponentReceiver::class.java).apply {
        data = Uri.parse("snapstone-print-choice:${token.jobId}:${token.index}")
    },
    PendingIntent.FLAG_MUTABLE
).intentSender

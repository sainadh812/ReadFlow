package app.readflow.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun SupportTipDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var amount by rememberSaveable { mutableStateOf("10") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val normalized = SupportTipDetails.normalizedAmount(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocalCafe, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Buy me a coffee") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enjoying Read Assit? You can leave an optional tip for its developer.")
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text("All current features are free. Tipping does not unlock features, perks or priority support.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("UPI recipient", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(SupportTipDetails.UPI_ID, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("10", "50", "100").forEach { preset ->
                        FilterChip(selected = normalized == "$preset.00", onClick = { amount = preset; feedback = null },
                            label = { Text("₹$preset") }, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; feedback = null },
                    label = { Text("Tip amount in rupees") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = normalized == null,
                    supportingText = { Text("₹1–₹1,000 · up to 2 decimal places (use a dot)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = normalized != null,
                    onClick = {
                        val parameters = SupportTipDetails.paymentParameters(amount)
                        if (parameters != null) {
                            val uri = Uri.Builder().scheme("upi").authority("pay").apply {
                                parameters.forEach { (key, value) -> appendQueryParameter(key, value) }
                            }.build()
                            try {
                                // Launch only. No payment callback, transaction storage or entitlement is involved.
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                feedback = "Continue in your UPI app. Read Assit does not verify payment status."
                            } catch (_: ActivityNotFoundException) {
                                feedback = "No app could open this UPI request. Copy the UPI ID and enter it in your payment app."
                            } catch (_: SecurityException) {
                                feedback = "Android blocked this UPI request. Copy the UPI ID and enter it in your payment app."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open UPI app")
                }
                OutlinedButton(onClick = {
                    try {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Read Assit UPI ID", SupportTipDetails.UPI_ID))
                            feedback = "UPI ID copied. Paste it in your payment app and check the recipient before paying."
                        } else feedback = "Clipboard unavailable. You can select and copy the UPI ID above."
                    } catch (_: SecurityException) {
                        feedback = "Clipboard access was blocked. You can select and copy the UPI ID above."
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy UPI ID")
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text("Check the UPI ID, recipient name and amount in your payment app before confirming. Some apps may reject this request; you can enter the ID manually instead. Payment confirmation and any PIN stay in your UPI app.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

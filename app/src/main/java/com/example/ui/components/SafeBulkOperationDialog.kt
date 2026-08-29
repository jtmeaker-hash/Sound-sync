package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.FileOperationType
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SafeBulkOperationDialog(
    operationType: FileOperationType,
    affectedCount: Int,
    currentPath: String,
    initialDryRun: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (targetDir: String, isDryRun: Boolean) -> Unit
) {
    var targetDirectory by remember { mutableStateOf(if (operationType == FileOperationType.MOVE) "$currentPath/Sorted" else currentPath) }
    var isDryRun by remember { mutableStateOf(initialDryRun) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("safe_bulk_operation_dialog"),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = "SAFE BULK ${operationType.name}",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // Summary Stats
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Files Affected:", color = TextSecondary, fontSize = 11.sp)
                            Text("$affectedCount tracks", color = DeckACyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Action:", color = TextSecondary, fontSize = 11.sp)
                            Text(operationType.label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Conflicts Detected:", color = TextSecondary, fontSize = 11.sp)
                            Text("0 conflicts", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (operationType == FileOperationType.MOVE) {
                    OutlinedTextField(
                        value = targetDirectory,
                        onValueChange = { targetDirectory = it },
                        label = { Text("Target Directory Path", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )
                }

                // Dry Run Switcher Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDryRun) NeonAmber.copy(alpha = 0.15f) else DjSurfaceElevated,
                    border = if (isDryRun) androidx.compose.foundation.BorderStroke(1.dp, NeonAmber) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Science, contentDescription = null, tint = if (isDryRun) NeonAmber else TextSecondary, modifier = Modifier.size(18.dp))
                            Column {
                                Text("DRY RUN SIMULATION", color = if (isDryRun) NeonAmber else TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Preview without altering files", color = TextSecondary, fontSize = 9.sp)
                            }
                        }
                        Switch(
                            checked = isDryRun,
                            onCheckedChange = { isDryRun = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonAmber,
                                checkedTrackColor = NeonAmber.copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextSecondary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            onConfirm(targetDirectory, isDryRun)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.5f).testTag("confirm_bulk_operation_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDryRun) NeonAmber else DeckACyan,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(if (isDryRun) Icons.Default.Science else Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isDryRun) "SIMULATE DRY RUN" else "CONFIRM ${operationType.name}",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

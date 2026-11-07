package com.recall.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HelpBackground = Color(0xFF100F17)
private val HelpCard = Color(0xFF201E2B)
private val HelpPurple = Color(0xFFB69CFF)
private val HelpMuted = Color(0xFFAAA6B8)

@Composable
fun RecallHelpScreen(
    onBack: () -> Unit
) {
    BackHandler {
        onBack()
    }

    val sections = listOf(
        "1. Getting started" to
                "Recall helps you save things you want to remember. Capture something from your screen, adjust the crop, add a title or notes, choose a category, and send it to your Library.",

        "2. Screen capture permission" to
                "When you capture something, Android displays its own screen-capture permission prompt. Open the selection dropdown and choose Entire screen. Then tap Start now or OK, depending on your Android version. Recall uses the permission to capture the selected screen temporarily so you can crop and save the part you need. This is not live screen-sharing: your screen is not broadcast to another person.",

        "3. Triple-tap gesture" to
                "When enabled, triple tap the back of your phone to open the Recall popup. Recall needs permission to display its popup over other apps and may need notification permission for its detection service. You can turn gesture detection off at any time in Settings.",

        "4. Sensitivity" to
                "Use the Triple-Tap Sensitivity slider in Settings to adjust how firmly you need to tap. A firmer setting requires a stronger tap; a more sensitive setting responds to lighter taps.",

        "5. Library and Search" to
                "Your Library contains your saved images. Open a memory to view its details. You can organise memories using categories and search using available titles, notes, categories, recognised image text, and other indexed information.",

        "6. Recall Account" to
                "Choose a Google account under Recall Account to sign in. This is Recall's account connection. It syncs saved memory details through Recall's cloud-sync system. Signing in here does not, by itself, upload image files to Google Drive or Google Photos.",

        "7. Google Drive and Google Photos" to
                "Drive and Photos are separate connections. Connect Google Drive to let Recall prepare a dedicated Recall folder in your Drive. Connect Google Photos separately to let Recall upload new captured images to your Photos library. You can connect either service from Settings.",

        "8. Cloud Backup" to
                "Under Cloud Backup, choose Google Drive or Google Photos as the destination. Connect the selected service, enable Automatic Backup, and choose Daily, Weekly, or Monthly. Automatic backup only runs when enabled. If the selected service is not authorised or an upload fails, the image remains on your device.",

        "9. Backing up images manually" to
                "Open Library, enter selection mode, select the images you want, and tap Backup. Recall uses the destination currently selected in Cloud Backup. The selected Drive or Photos account must be connected. The result message shows how many uploads succeeded and failed.",

        "10. Checking backup status" to
                "Each image card in Library displays its backup status below its title and category. Not backed up means it has not been uploaded to the selected destination. Pending means an upload is waiting or in progress. Backed up means the upload completed. Failed means the upload did not complete. You can retry by selecting the image in Library and using Backup."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HelpBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(
                    "← Back",
                    color = HelpPurple
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                "Help",
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            "Learn how to capture, organise, search, and back up your memories.",
            color = HelpMuted,
            fontSize = 14.sp
        )

        sections.forEach { (heading, description) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = HelpCard
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        heading,
                        color = HelpPurple,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        description,
                        color = HelpMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Your memories remain stored locally on this device even when a cloud connection is unavailable. Cloud backup is an additional copy, not a replacement for local storage.",
            color = HelpMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(12.dp))
    }
}
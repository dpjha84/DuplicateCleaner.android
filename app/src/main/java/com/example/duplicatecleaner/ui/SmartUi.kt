package com.example.duplicatecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.duplicatecleaner.data.DuplicateGroup

/** What the UI can do about Smart's uncertain groups. */
data class SmartActions(
    val markReviewed: (DuplicateGroup) -> Unit,
    val toggleReviewOnly: () -> Unit
)

/** Tells the user how many groups Smart wasn't sure about, with a filter to see only those. */
@Composable
fun ReviewCard(count: Int, reviewOnly: Boolean, actions: SmartActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (count == 1) "1 group needs a quick look" else "$count groups need a quick look",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "Smart couldn't tell which copy matters more in these. Everything else is already decided.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = actions.toggleReviewOnly) {
                Text(if (reviewOnly) "Show all groups" else "Show only these")
            }
        }
    }
}

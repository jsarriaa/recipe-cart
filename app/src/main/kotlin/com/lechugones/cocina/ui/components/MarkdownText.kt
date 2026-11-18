package com.lechugones.cocina.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lechugones.cocina.domain.markdown.Markdown
import com.lechugones.cocina.domain.markdown.MdBlock
import com.lechugones.cocina.domain.markdown.MdSpan

/** Renders the recipe Markdown subset (RECIPE_FORMAT § 8). Never loads HTML or remote content. */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Text(
                        text = block.spans.annotated(),
                        style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(block.spans.annotated(), style = MaterialTheme.typography.bodyLarge)
                }

                is MdBlock.OrderedList -> {
                    ListItems(block.items) { index -> "${block.start + index}." }
                }

                is MdBlock.BulletList -> {
                    ListItems(block.items) { "•" }
                }
            }
        }
    }
}

@Composable
private fun ListItems(
    items: List<List<MdSpan>>,
    marker: (Int) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    marker(index),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 24.dp),
                )
                Text(item.annotated(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun List<MdSpan>.annotated(): AnnotatedString =
    buildAnnotatedString {
        forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                ),
            ) { append(span.text) }
        }
    }

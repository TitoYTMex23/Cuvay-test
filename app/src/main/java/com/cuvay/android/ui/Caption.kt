package com.cuvay.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withLink
private val linkRe = Regex("""(https?://[^\s]+)|(@[A-Za-z0-9_]{4,32})|(#[\p{L}\p{N}_]{2,40})""")
// "🎬 Clave: valor"  ->  prefijo (emoji), clave, valor
private val kvRe = Regex("""^(\P{L}{0,6}?)(\p{L}[\p{L} ]{0,20}):\s*(.*)$""")
private val linkStyle = TextLinkStyles(SpanStyle(color = Blue, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline))

private fun AnnotatedString.Builder.appendLinked(s: String) {
    var last = 0
    for (m in linkRe.findAll(s)) {
        append(s.substring(last, m.range.first))
        val raw = m.value
        when {
            raw.startsWith("http") -> {
                val url = raw.trimEnd('.', ',', ')', ';')
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                append(raw.substring(url.length))
            }
            raw.startsWith("@") -> withLink(LinkAnnotation.Url("https://t.me/" + raw.substring(1), linkStyle)) { append(raw) }
            else -> withStyle(SpanStyle(color = Blue, fontWeight = FontWeight.Bold)) { append(raw) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

/** Primera linea = titulo grande; "Clave: valor" = clave en negrita; @usuario, #tag y URLs con color/enlace. */
fun buildCaption(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.trimEnd()
        val kv = if (i == 0) null else kvRe.find(line)
        when {
            i == 0 -> withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)) { appendLinked(line) }
            kv != null -> {
                append(kv.groupValues[1])
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Muted)) { append(kv.groupValues[2] + ": ") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendLinked(kv.groupValues[3]) }
            }
            else -> appendLinked(line)
        }
    }
}

@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    var open by remember(text) { mutableStateOf(false) }
    val limit = 360
    val long = text.length > limit
    val shown = if (long && !open) text.take(limit).trimEnd() + "…" else text
    val styled = remember(shown) { buildCaption(shown) }
    Column(modifier) {
        Text(styled, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
        if (long) Text(if (open) "VER MENOS" else "VER MÁS", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 6.dp).clickable { open = !open })
    }
}

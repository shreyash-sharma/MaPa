package app.mapa.family.ui

import android.content.Intent
import android.graphics.Bitmap
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mapa.core.language.Lang
import app.mapa.core.pairing.InviteCodes
import app.mapa.family.FamilyRepo
import app.mapa.shared.ui.PrimaryButton
import app.mapa.shared.ui.SecondaryButton
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

/** The link a parent taps. A custom scheme until MaPa has a verified web domain for App Links. */
fun inviteLink(code: String) = "mapa://join/$code"

/**
 * Family-started setup. The family member fills everything in; the parent's phone only asks
 * "Yes, share?". Two steps: describe the parent, then send the link (or show the QR in person).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddParentScreen(text: FamilyText, onBack: () -> Unit, onHaveCode: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("other") }
    var lang by rememberSaveable { mutableStateOf(text.lang.code) }
    var languageQuery by rememberSaveable { mutableStateOf("") }
    var languageMenuOpen by rememberSaveable { mutableStateOf(false) }
    var phone by rememberSaveable { mutableStateOf("") }
    var contactName by rememberSaveable { mutableStateOf("") }
    var myName by rememberSaveable { mutableStateOf(FamilyRepo.firstName ?: "") }
    var code by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Picking from Phone.CONTENT_URI grants a one-time read of that number, no contacts permission.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                phone = c.getString(0) ?: ""
                contactName = c.getString(1) ?: ""
            }
        }
    }

    val accepted by remember(code) { code?.let { FamilyRepo.pairingAccepted(it) } ?: flowOf(null) }.collectAsState(initial = null)
    LaunchedEffect(accepted) {
        val pid = accepted ?: return@LaunchedEffect
        FamilyRepo.completePairing(pid)
        if (phone.isNotBlank()) FamilyRepo.savePhone(ctx, pid, phone)
        onDone(pid)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TopBar(start = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text.back) } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            val c = code
            if (c == null) {
                Spacer(Modifier.height(Space.l))
                Text(text.setupTitle, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(Space.s))
                Text(text.setupBody, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.xl))
                Text(text.whatCall, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.s))
                // One tap for the usual names; the box below takes anything else ("Nani", "Bauji", a first name).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    text.callNames.forEach { (label, rel) ->
                        Choice(label, name == label) { name = label; relation = rel }
                    }
                }
                Spacer(Modifier.height(Space.m))
                OutlinedTextField(
                    name, { name = it.take(24); relation = text.callNames.firstOrNull { (l, _) -> l == name }?.second ?: "other" },
                    label = { Text(text.whatCallOther) }, placeholder = { Text(text.whatCallHint) },
                    singleLine = true, shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.xl))
                Text(text.theirLanguage, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.s))
                val languages = remember {
                    Lang.entries.sortedBy { Locale.forLanguageTag(it.code).getDisplayLanguage(Locale.ENGLISH) }
                }
                val selectedLanguage = languages.firstOrNull { it.code == lang } ?: Lang.EN
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = if (languageMenuOpen) languageQuery else languageLabel(selectedLanguage),
                        onValueChange = { languageQuery = it; languageMenuOpen = true },
                        label = { Text(text.theirLanguage) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                            if (state.isFocused && !languageMenuOpen) {
                                languageQuery = ""
                                languageMenuOpen = true
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = languageMenuOpen,
                        onDismissRequest = { languageMenuOpen = false; languageQuery = "" },
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        languages.filter { languageLabel(it).contains(languageQuery, ignoreCase = true) }
                            .forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(languageLabel(choice)) },
                                    onClick = { lang = choice.code; languageMenuOpen = false; languageQuery = "" },
                                )
                            }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Text(text.theirNumber, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Space.s))
                SecondaryButton(
                    if (phone.isBlank()) text.chooseContact else listOf(contactName, phone).filter { it.isNotBlank() }.joinToString("  "),
                    { pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                    leading = { Icon(Icons.Filled.Person, null, Modifier.size(20.dp)) },
                )
                Text(text.numberWhy, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
                Spacer(Modifier.height(Space.xl))
                OutlinedTextField(
                    myName, { myName = it.take(60) }, label = { Text(text.yourNameLabel) },
                    singleLine = true, shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onHaveCode, modifier = Modifier.padding(top = Space.l).heightIn(min = 48.dp)) {
                    Text(text.codeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val who = name.ifBlank { text.callNames.first().first }
                Spacer(Modifier.height(Space.l))
                Text(text.sendTitle(who), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(Space.s))
                Text(text.sendBody, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.xl))
                Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    val qr = remember(c) { qrBitmap(inviteLink(c), 640) }
                    Image(qr.asImageBitmap(), contentDescription = null, modifier = Modifier.padding(Space.l).size(220.dp))
                }
                Spacer(Modifier.height(Space.m))
                Text(
                    InviteCodes.display(c),
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 4.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(Space.xl))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Space.m))
                    Text(text.waiting(who), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l)) {
            val c = code
            if (c == null) {
                PrimaryButton(
                    text.createInvite,
                    enabled = !busy && name.isNotBlank() && myName.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            code = FamilyRepo.createPairing(name, relation, lang, myName)
                            busy = false
                        }
                    },
                )
            } else {
                PrimaryButton(text.sendWhatsApp, {
                    val msg = text.shareText(name, inviteLink(c), InviteCodes.display(c))
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                    val whatsapp = Intent(send).setPackage("com.whatsapp")
                    runCatching { ctx.startActivity(whatsapp) }.onFailure { ctx.startActivity(Intent.createChooser(send, text.sendWhatsApp)) }
                }, leading = { Icon(Icons.Filled.Share, null, Modifier.size(20.dp)) })
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, shape = CircleShape,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Space.m)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

private fun languageLabel(lang: Lang): String {
    val locale = Locale.forLanguageTag(lang.code)
    val native = locale.getDisplayLanguage(locale).ifBlank { lang.code }
    val english = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { lang.code }
    return if (native.equals(english, ignoreCase = true)) english else "$native · $english"
}

private fun qrBitmap(content: String, px: Int): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF1F2A24.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}

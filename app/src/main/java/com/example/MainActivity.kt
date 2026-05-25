package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

// Data model for each file being converted
data class ConversionItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val sourceFileName: String,
    val sourceSizeBytes: Long,
    val convertedFile: File? = null,
    val convertedSizeBytes: Long = 0L,
    val isConverting: Boolean = false,
    val error: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle initial incoming images from share intent
        val initialUris = mutableListOf<Uri>()
        val action = intent?.action
        if (action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                initialUris.add(uri)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (uris != null) {
                initialUris.addAll(uris)
            }
        }

        // Detect if another app is requesting an image (browser choosing files)
        val isPickerMode = action == Intent.ACTION_GET_CONTENT || action == Intent.ACTION_PICK

        setContent {
            MyApplicationTheme {
                MainScreen(
                    initialUris = initialUris,
                    isPickerMode = isPickerMode,
                    onGetContentResult = { webpUri ->
                        val resultIntent = Intent().apply {
                            data = webpUri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    },
                    onBackClicked = {
                        finish()
                    }
                )
            }
        }
    }
}

// Helper to extract file name and physical size from URIs securely
fun getUriNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "gorsel_${System.currentTimeMillis() % 10000}.png"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    if (size == 0L) {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                size = afd.length
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return Pair(name, size)
}

// Pure Kotlin core conversion handler - scales and packages bitmap to temporary App caches
fun convertImageToWebP(
    context: Context,
    sourceUri: Uri,
    quality: Int,
    scalePercent: Int,
    onStart: () -> Unit,
    onSuccess: (File, Long) -> Unit,
    onFailure: (String) -> Unit
) {
    onStart()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw Exception("Görsel açılamadı")
            var bitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw Exception("Görsel çözümlenemedi veya bozuk")
            inputStream.close()

            // Handle optional downscaling/resizing
            if (scalePercent in 10..99) {
                val scale = scalePercent / 100f
                val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
            }

            // Create unique cache files inside /cache/converted
            val outputDir = File(context.cacheDir, "converted").apply { mkdirs() }
            val cleanName = "converted_${System.currentTimeMillis()}_${UUID.randomUUID().hashCode() % 100}.webp"
            val outputFile = File(outputDir, cleanName)

            val outputStream = FileOutputStream(outputFile)
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val success = bitmap.compress(format, quality, outputStream)
            outputStream.flush()
            outputStream.close()
            bitmap.recycle()

            if (!success) {
                throw Exception("WebP sıkıştırma aşamasında hata oluştu")
            }

            val size = outputFile.length()
            withContext(Dispatchers.Main) {
                onSuccess(outputFile, size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onFailure(e.message ?: "Beklenmeyen dönüştürme hatası")
            }
        }
    }
}

// Standard MediaStore handler to write final files directly to public Pictures album (no storage permissions required since Q)
fun saveFileToGallery(context: Context, file: File, originalName: String, onComplete: (Boolean) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val targetName = originalName.substringBeforeLast(".") + "_webp.webp"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/webp")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WebPHaber")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val contentResolver = context.contentResolver
            val systemUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(systemUri, contentValues)

            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onComplete(false)
            }
        }
    }
}

// Sharing single image safely via FileProvider
fun shareSingleFile(context: Context, file: File, displayName: String) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val shareUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Görseli WebP Olarak Paylaş"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Sistem paylaşım menüsü açılamadı", Toast.LENGTH_SHORT).show()
    }
}

// Sharing bulk images at once
fun shareMultipleFiles(context: Context, files: List<File>) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>()
        for (f in files) {
            uris.add(FileProvider.getUriForFile(context, authority, f))
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/webp"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Görselleri WebP Olarak Paylaş"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Toplu paylaşım başarısız oldu", Toast.LENGTH_SHORT).show()
    }
}

// Format bytes size to readable metric (e.g. 5.12 MB)
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialUris: List<Uri>,
    isPickerMode: Boolean,
    onGetContentResult: (Uri) -> Unit,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Main States
    val conversionItems = remember { mutableStateListOf<ConversionItem>() }
    var quality by remember { mutableStateOf(80) }
    var scalePercent by remember { mutableStateOf(100) }

    // Sequential index-safe single conversion worker
    fun convertSingle(item: ConversionItem, curQuality: Int, curScale: Int) {
        val index = conversionItems.indexOfFirst { it.id == item.id }
        if (index != -1) {
            conversionItems[index] = conversionItems[index].copy(isConverting = true, error = null)
            convertImageToWebP(
                context = context,
                sourceUri = item.sourceUri,
                quality = curQuality,
                scalePercent = curScale,
                onStart = { /* Started */ },
                onSuccess = { file, size ->
                    val checkIndex = conversionItems.indexOfFirst { it.id == item.id }
                    if (checkIndex != -1) {
                        conversionItems[checkIndex] = conversionItems[checkIndex].copy(
                            isConverting = false,
                            convertedFile = file,
                            convertedSizeBytes = size,
                            error = null
                        )
                    }
                },
                onFailure = { errorMsg ->
                    val checkIndex = conversionItems.indexOfFirst { it.id == item.id }
                    if (checkIndex != -1) {
                        conversionItems[checkIndex] = conversionItems[checkIndex].copy(
                            isConverting = false,
                            error = errorMsg
                        )
                    }
                }
            )
        }
    }

    // Convert everything with current sliders settings
    fun convertAllItems() {
        conversionItems.forEach { item ->
            convertSingle(item, quality, scalePercent)
        }
    }

    // Populate initial URIs if shared or pre-loaded
    LaunchedEffect(initialUris) {
        if (initialUris.isNotEmpty() && conversionItems.isEmpty()) {
            val newList = initialUris.map { uri ->
                val (name, size) = getUriNameAndSize(context, uri)
                ConversionItem(sourceUri = uri, sourceFileName = name, sourceSizeBytes = size)
            }
            conversionItems.addAll(newList)
            newList.forEach { item ->
                convertSingle(item, quality, scalePercent)
            }
        }
    }

    // Multiple file picker launcher
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            val newList = uris.map { uri ->
                val (name, size) = getUriNameAndSize(context, uri)
                ConversionItem(sourceUri = uri, sourceFileName = name, sourceSizeBytes = size)
            }
            conversionItems.addAll(newList)
            newList.forEach { item ->
                convertSingle(item, quality, scalePercent)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "WebP Görsel Dönüştürücü",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Haber Sitesi Görsel Boyut Optimizasyonu (Maks. 2MB)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ),
                navigationIcon = {
                    if (isPickerMode || conversionItems.isNotEmpty()) {
                        IconButton(onClick = onBackClicked) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                        }
                    }
                },
                actions = {
                    if (conversionItems.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                conversionItems.clear()
                                Toast.makeText(context, "Liste temizlendi", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Tümünü Temizle", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // High visibility warning banner for Picker mode (when browsed/activated inside other apps)
            if (isPickerMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE5A93C))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Bilgi",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sitenizin yükleme arayüzündesiniz. Fotoğrafı seçip dönüştürdükten sonra siteye doğrudan gönderebilirsiniz.",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Calculations summary headers
            if (conversionItems.isNotEmpty()) {
                val totalOriginalSize = conversionItems.sumOf { it.sourceSizeBytes }
                val totalConvertedSize = conversionItems.sumOf { if (it.convertedFile != null) it.convertedSizeBytes else 0L }
                val spaceSaved = (totalOriginalSize - totalConvertedSize).coerceAtLeast(0L)
                val savingsRatio = if (totalOriginalSize > 0) (spaceSaved.toFloat() / totalOriginalSize * 100).toInt() else 0
                val allUnder2MB = conversionItems.all { it.convertedFile == null || it.convertedSizeBytes <= 2 * 1024 * 1024 }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mevcut Optimizasyon Özeti",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (allUnder2MB && totalConvertedSize > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color(0xFF2E7D32), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Hazır", tint = Color.White, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("HEPSİ HAZIR (<2MB)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            } else if (totalConvertedSize > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(Color(0xFFC62828), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = "Uyarı", tint = Color.White, modifier = Modifier.size(10.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("2MB SINIRI AŞILDI!", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Orijinal Boyut", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                Text(formatSize(totalOriginalSize), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Column {
                                Text("WebP Boyutu", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                Text(
                                    text = if (totalConvertedSize > 0) formatSize(totalConvertedSize) else "Hesaplanıyor...",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (allUnder2MB) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Toplam Tasarruf", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                                Text(
                                    text = "%$savingsRatio Azalma",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                }
            }

            // Configuration Squeeze Adjusters Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Compress quality slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sıkıştırma Oranı (Kalite): %$quality",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                quality > 85 -> "Yüksek Kalite / Büyük Boyut"
                                quality in 70..85 -> "Önerilen / İdeal Denge"
                                else -> "Düşük Kalite / Çok Küçük Boyut"
                            },
                            fontSize = 10.sp,
                            color = if (quality in 70..85) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = quality.toFloat(),
                        valueRange = 10f..100f,
                        onValueChange = { quality = it.toInt() },
                        onValueChangeFinished = { convertAllItems() },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Custom Scaling Dimensions Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Haber Görseli Boyut Küçültme: %$scalePercent",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (scalePercent == 100) "Orijinal Çözünürlük" else "Yeniden Boyutlandırıldı (${scalePercent}%)",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = scalePercent.toFloat(),
                        valueRange = 20f..100f,
                        onValueChange = { scalePercent = it.toInt() },
                        onValueChangeFinished = { convertAllItems() },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.secondary,
                            activeTrackColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }

            // Main Listing Content Zone
            if (conversionItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .border(
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { pickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Upload",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Haber Görsellerini Seçin",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Cihazınızdaki PNG, JPG, JPEG veya WebP haber fotoğraflarını seçmek için buraya basın.",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { pickerLauncher.launch("image/*") },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ekle", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Fotoğrafları Yükle")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversionItems, key = { it.id }) { item ->
                        ListItemCard(
                            item = item,
                            onSave = {
                                if (item.convertedFile != null) {
                                    saveFileToGallery(context, item.convertedFile, item.sourceFileName) { success ->
                                        if (success) {
                                            Toast.makeText(context, "Medyaya başarıyla kaydedildi!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Kaydetme başarısız oldu", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onShare = {
                                if (item.convertedFile != null) {
                                    shareSingleFile(context, item.convertedFile, item.sourceFileName)
                                }
                            },
                            onRemove = {
                                conversionItems.remove(item)
                            },
                            onReConvert = {
                                convertSingle(item, quality, scalePercent)
                            }
                        )
                    }

                    // Bottom list add extra item button
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pickerLauncher.launch("image/*") },
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Ekle", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Listeye Daha Fazla Fotoğraf Ekle", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Squeeze execute actions dock bottom panel
            if (conversionItems.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val readyToExport = conversionItems.filter { it.convertedFile != null }
                        val allReady = readyToExport.size == conversionItems.size

                        if (isPickerMode) {
                            // Picker Mode Return trigger
                            Button(
                                onClick = {
                                    val firstFile = readyToExport.firstOrNull()?.convertedFile
                                    if (firstFile != null) {
                                        try {
                                            val authority = "${context.packageName}.fileprovider"
                                            val webpUri = FileProvider.getUriForFile(context, authority, firstFile)
                                            onGetContentResult(webpUri)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(context, "Paylaşım hatası oluştu", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Lütfen en az 1 dosyanın dönüşmesini bekleyin", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = readyToExport.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                            ) {
                                Icon(Icons.Default.Done, contentDescription = "Gönder")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Seçilen WebP’yi Haber Sitesine Gönder (${readyToExport.size} Dosya)")
                            }
                        } else {
                            // Standard Multi-Actions save/share dock
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val filesList = readyToExport.map { it.convertedFile!! }
                                        if (filesList.isNotEmpty()) {
                                            shareMultipleFiles(context, filesList)
                                        } else {
                                            Toast.makeText(context, "Sıkıştırılmış dosya henüz hazır değil", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = readyToExport.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Paylaş", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Tümünü Paylaş")
                                }

                                Button(
                                    onClick = {
                                        var savedCounter = 0
                                        readyToExport.forEach { item ->
                                            saveFileToGallery(context, item.convertedFile!!, item.sourceFileName) { success ->
                                                if (success) {
                                                    savedCounter++
                                                    if (savedCounter == readyToExport.size) {
                                                        Toast.makeText(context, "Tüm görseller galeriye başarıyla kaydedildi!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = readyToExport.isNotEmpty(),
                                    modifier = Modifier.weight(1.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Tümünü Kaydet", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Tümünü Galeriye Kaydet")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ListItemCard(
    item: ConversionItem,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onReConvert: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: File name options
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.sourceFileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Compression analysis area (Before/After)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Orijinal Boyut", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatSize(item.sourceSizeBytes), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Arrow",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text("Dönüştürülmüş WebP", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.isConverting) {
                        Box(modifier = Modifier.size(16.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.fillMaxSize())
                        }
                    } else if (item.error != null) {
                        Text(item.error ?: "Hata oluştu", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    } else if (item.convertedFile != null) {
                        val isSafe = item.convertedSizeBytes <= 2 * 1024 * 1024
                        Text(
                            text = formatSize(item.convertedSizeBytes),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSafe) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    } else {
                        Text("Dönüştürülüyor...", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Visual labels, warnings and unique actions per item
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Safety badge (Under or over 2MB site limit)
                if (item.convertedFile != null) {
                    val isSafe = item.convertedSizeBytes <= 2 * 1024 * 1024
                    val savings = ((item.sourceSizeBytes - item.convertedSizeBytes).toFloat() / item.sourceSizeBytes * 100).toInt().coerceAtLeast(0)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSafe) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("UYGUN (<2MB)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("HATA (>2MB)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                            }
                        }

                        if (savings > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("-%$savings tasarruf", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                } else if (item.error != null) {
                    IconButton(
                        onClick = onReConvert,
                        modifier = Modifier.height(24.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Tekrar Dene", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Individual Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onShare,
                        enabled = item.convertedFile != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Paylaş", modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Paylaş", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onSave,
                        enabled = item.convertedFile != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Kaydet", modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Galeriye Kaydet", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

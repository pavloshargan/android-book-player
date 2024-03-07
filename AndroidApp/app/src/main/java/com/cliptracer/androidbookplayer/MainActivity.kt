package com.cliptracer.androidbookplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.result.ActivityResultLauncher
import com.cliptracer.androidbookplayer.ui.theme.AndroidBookPlayerTheme
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.*
import okio.IOException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val sampleFileUrl = "https://raw.githubusercontent.com/brunoklein99/deep-learning-notes/master/shakespeare.txt"
    private var textUrl by mutableStateOf(sampleFileUrl)

    private lateinit var tts: TextToSpeech
    private val sentencesQueue = ConcurrentLinkedQueue<String>()
    private var textToRead by mutableStateOf("")

    private var speechRate by mutableStateOf(1.0f) // Normal speed is 1.0


    private var voice: Voice? = null
    private var selectedVoice by mutableStateOf(voice)
    private val availableVoices = mutableStateListOf<Voice>()

    private var currentSentenceIndex by mutableStateOf(0) // Track the index of the current sentence
    private var isInitialized by mutableStateOf(false) // Track if TTS is initialized

    private var showDialog by mutableStateOf(false)

    private var numSentencesToRead by mutableStateOf(3) // Default is 3

    private val client = OkHttpClient()

    // Extract TTS initialization to its own method
    private fun initializeTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts.setLanguage(Locale.US)

                // Load voices here
                val voices = tts.voices
                if (!voices.isNullOrEmpty()) {
                    availableVoices.addAll(voices)
//                    selectedVoice = tts.voice ?: voices.first()
                    selectedVoice = tts.defaultVoice
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeTTS()
        tts = TextToSpeech(this, this).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String) {
                    if (!sentencesQueue.isEmpty()) {
                        speakOut(sentencesQueue.poll())
                    }
                }

                override fun onError(utteranceId: String?) {}
            })
        }

        val pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    readTextFile(uri)
                }
            }
        }

        setContent {
            AndroidBookPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black // Set background color to black
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(), // Remove padding
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Your existing content here
                        Button(onClick = { showDialog = true }) {
                            Text("Select a Book", color = Color.White) // Set text color to soft white
                        }

                        NumberOfSentencesSlider(numSentencesToRead)
                        Text("Current Speed: ${String.format("%.1f", speechRate)}", color = Color.White) // Set text color to soft white
                        Slider(
                            value = speechRate,
                            onValueChange = {
                                speechRate = it
                                if (isInitialized && tts.isSpeaking) {
                                    // Stop speaking the current sentence
                                    tts.stop()
                                    // Reinitialize TTS with the new voice
                                    // Continue speaking from the current index
                                    continueSpeakingFromIndex(currentSentenceIndex - 1)
                                }
                            },
                            valueRange = 0.5f..5.0f, // Extend the range to 5
                            steps = 45
                        )
                        DropdownMenuVoiceSelection()
                        Button(onClick = { speakOut(textToRead) }) {
                            Text("Speak", color = Color.White) // Set text color to soft white
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize() // Remove padding
                        ) {
                            item {
                                Text(
                                    text = textToRead,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White // Set text color to soft white
                                )
                            }
                        }
                    }
                }
            }
            if (showDialog) {
                showBookSelectionDialog(pickFileLauncher) { showDialog = false }
            }
        }


    }






    @Composable
    fun showBookSelectionDialog(
        pickFileLauncher: ActivityResultLauncher<Intent>,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = {
                onDismiss()
            },
                title = { Text("Select a Book") },
                text = {
                    Column {
                        Text("Choose how you want to select your book:")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { pickFile(pickFileLauncher); showDialog = false }) {
                            Text("Select from Files")
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = textUrl,
                            onValueChange = { textUrl = it },
                            label = { Text("Enter URL") },
                            placeholder = { Text(sampleFileUrl) }
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { loadTextFromUrl(textUrl); showDialog = false }) {
                            Text("Load from URL")
                        }
                    }
                },
                confirmButton = { }
            )
    }

    private fun loadTextFromUrl(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    val textData = response.body?.string() ?: ""
                    withContext(Dispatchers.Main) {
                        textToRead = textData
                        // You may want to update any UI elements or state variables here
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Handle the exception, maybe update the UI to show an error message
            }
        }
    }
    @Composable
    fun DropdownMenuVoiceSelection() {
        var expanded by remember { mutableStateOf(false) }
        val voiceNames = availableVoices.map { it.name }.sorted()
        var selectedVoiceName by remember { mutableStateOf(selectedVoice?.name ?: "") }

        LaunchedEffect(selectedVoice) {
            if (selectedVoice != null) {
                selectedVoiceName = selectedVoice!!.name
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Voice Style", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp), color = Color.White // Set text color to soft white
            )
            TextButton(onClick = { expanded = true }) {
                Text(text = selectedVoiceName.ifEmpty { "Select a voice" })
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            voiceNames.forEach { voiceName ->
                DropdownMenuItem(
                    onClick = {
                        selectedVoiceName = voiceName
                        selectedVoice = availableVoices.first { it.name == voiceName }
                        expanded = false

                        if (isInitialized && tts.isSpeaking) {
                            tts.stop()
                            continueSpeakingFromIndex(currentSentenceIndex-1)
                        }
                    },
                    text = {
                        Text(text = voiceName)
                    }
                )
            }
        }
    }


    @Composable
    fun NumberOfSentencesSlider(numSentencesState: Int) {
        Column {
            Text("Number of Sentences to Read at a Time: ${numSentencesState}",color = Color.White
            )
            Slider(
                value = numSentencesState.toFloat(),
                onValueChange = { numSentencesToRead = it.toInt() },
                valueRange = 1f..10f, // Assuming a range between 1 and 10
                steps = 9
            )
        }
    }



    private fun continueSpeakingFromIndex(index: Int) {
        // This function needs to start speaking from the given index
        val sentencesList = sentencesQueue.toList()
        currentSentenceIndex = index
        speakOut(sentencesList.subList(index, sentencesList.size).joinToString(" "))
    }


    private fun speakOut(text: String, numSentences: Int = 3) { // numSentences is the number of sentences to read at a time
        if (text.isNotEmpty() && isInitialized) {
            tts.setSpeechRate(speechRate)
            tts.voice = selectedVoice ?: tts.defaultVoice
            if (sentencesQueue.isEmpty()) {
                val sentenceDelimiterRegex = Regex("(?<=[.])\\s+|(?<=[.])$|\\n")
                sentencesQueue.addAll(sentenceDelimiterRegex.split(text).filter { it.isNotBlank() })
            }
            var sentencesToSpeak = ""
            for (i in 0 until numSentences) {
                val nextSentence = sentencesQueue.poll() // Retrieves and removes the head of the queue
                if (nextSentence != null) {
                    sentencesToSpeak += "$nextSentence "
                    currentSentenceIndex++
                }
            }
            if(sentencesToSpeak.isNotEmpty()) {
                tts.speak(sentencesToSpeak, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            }
        }
    }



    private fun pickFile(pickFileLauncher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        pickFileLauncher.launch(intent)
    }

    private fun readTextFile(uri: Uri) {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.reader().use { reader ->
                stringBuilder.append(reader.readText())
            }
        }
        textToRead = stringBuilder.toString()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts.setLanguage(Locale.US)
            // Initialize availableVoices with available TTS voices
            availableVoices.addAll(tts.voices ?: emptySet())
            // Default to the current voice or the first available one
            selectedVoice = tts.defaultVoice
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

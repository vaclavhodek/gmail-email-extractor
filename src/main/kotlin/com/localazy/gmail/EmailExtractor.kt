package com.localazy.gmail

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext


private const val TIMEOUT = 15000
private const val APPLICATION_NAME = "Email Extractor"
private val JSON_FACTORY: JsonFactory = JacksonFactory.getDefaultInstance()
private const val TOKENS_DIRECTORY_PATH = "tokens"
private val SCOPES = setOf(
        GmailScopes.GMAIL_LABELS,
        GmailScopes.GMAIL_READONLY,
        GmailScopes.GMAIL_METADATA
)


class EmailExtractor {


    private val MAX_FETCH_THREADS = Runtime.getRuntime().availableProcessors()

    val executors = Executors.newFixedThreadPool(MAX_FETCH_THREADS)

    val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executors.execute(block)
        }
    }


    private fun getCredentials(httpTransport: HttpTransport): Credential? {
        val inputStream = File("credentials.json").inputStream()
        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(inputStream))
        val flow = GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build()
        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }


    private tailrec fun Gmail.processMessages(
            user: String,
            label: Label,
            nextPageToken: String? = null,
            process: (Message) -> Unit
    ) {

        val messages = users().messages().list(user).apply {
            labelIds = listOf(label.id)
            pageToken = nextPageToken
            includeSpamTrash = true
        }.execute()

        messages.messages.forEach { message ->
            process(message)
        }

        if (messages.nextPageToken != null) {
            processMessages(user, label, messages.nextPageToken, process)
        }
    }


    private fun String.parseAddress(): String {
        return if (contains("<")) {
            substringAfter("<").substringBefore(">")
        } else {
            this
        }
    }


    private fun Gmail.processFroms(
            user: String,
            label: Label,
            process: (String) -> Unit
    ) {
        runBlocking(dispatcher) {
            processMessages(user, label) { m ->
                launch {
                    fun fetchAndProcess() {
                        try {
                            val message = users().messages().get(user, m.id).apply { format = "METADATA" }.execute()
                            message.payload.headers.find { it.name == "From" }?.let { from ->
                                process(from.value.parseAddress())
                            }
                        } catch (e: SocketTimeoutException) {
                            // Process eventual failures.
                            // Restart request on socket timeout.
                            e.printStackTrace()
                            fetchAndProcess()
                        } catch (e: Exception) {
                            // Process eventual failures.
                            e.printStackTrace()
                        }
                    }
                    fetchAndProcess()
                }
            }
        }
    }


    fun extract(labelName: String) {

        // Build a new authorized API client service.
        val httpTransport = GoogleNetHttpTransport
                .newTrustedTransport()
                .createRequestFactory { request ->
                    request.connectTimeout = TIMEOUT
                    request.readTimeout = TIMEOUT
                }.transport

        val service = Gmail.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName(APPLICATION_NAME)
                .build()

        // Find the requested label
        val user = "me"
        val labelList = service.users().labels().list(user).execute()
        val label = labelList.labels
                .find { it.name == labelName } ?: error("Label `$labelName` is unknown.")


        // Process all From headers.
        val senders = mutableSetOf<String>()
        service.processFroms(user, label) {
            senders += it
        }

        senders.forEach(::println)
    }

}


fun main(args: Array<String>) {
    if (args.size != 1) {
        println("Please specify exactly one parameter - the label/folder you want to extract emails from.")
        return
    }

    EmailExtractor().extract(args[0])
}
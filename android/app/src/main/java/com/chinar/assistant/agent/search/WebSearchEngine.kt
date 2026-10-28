package com.chinar.assistant.agent.search

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Live, real-time web search and information retrieval engine.
 * Queries live web sources (Wikipedia API and DuckDuckGo Instant Answer API)
 * to provide factual answers, snippets, and source citations for Chinar's voice agent.
 */
class WebSearchEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "WebSearchEngine"
        private const val USER_AGENT = "ChinarVoiceAssistant/1.0 (Android; Mobile)"
    }

    data class SearchItem(
        val title: String,
        val snippet: String,
        val url: String,
        val source: String
    )

    data class SearchResult(
        val success: Boolean,
        val query: String,
        val items: List<SearchItem>,
        val summary: String,
        val errorMessage: String? = null
    ) {
        fun toPromptResponse(): JSONObject {
            return JSONObject().apply {
                put("status", if (success) "success" else "error")
                put("query", query)
                if (success) {
                    put("summary", summary)
                    val jsonItems = org.json.JSONArray()
                    for (item in items) {
                        jsonItems.put(JSONObject().apply {
                            put("title", item.title)
                            put("snippet", item.snippet)
                            put("url", item.url)
                            put("source", item.source)
                        })
                    }
                    put("sources", jsonItems)
                } else {
                    put("message", errorMessage ?: "Web search failed.")
                }
            }
        }
    }

    /**
     * Performs a real live web query across verified knowledge sources.
     */
    suspend fun search(query: String): SearchResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return SearchResult(
                success = false,
                query = query,
                items = emptyList(),
                summary = "",
                errorMessage = "Search query is empty."
            )
        }

        Log.d(TAG, "[WEB_SEARCH_START] Query: '$cleanQuery'")
        val results = mutableListOf<SearchItem>()

        // 1. Query Wikipedia Search API for authoritative factual information
        try {
            val wikiItems = searchWikipedia(cleanQuery)
            results.addAll(wikiItems)
        } catch (e: Exception) {
            Log.w(TAG, "[WIKI_SEARCH_ERROR] Wikipedia search failed for '$cleanQuery': ${e.message}")
        }

        // 2. Query DuckDuckGo Instant Answer API for quick definitions / abstracts
        try {
            val ddgItems = searchDuckDuckGo(cleanQuery)
            results.addAll(ddgItems)
        } catch (e: Exception) {
            Log.w(TAG, "[DDG_SEARCH_ERROR] DuckDuckGo search failed for '$cleanQuery': ${e.message}")
        }

        if (results.isEmpty()) {
            Log.w(TAG, "[WEB_SEARCH_EMPTY] No results found for query: '$cleanQuery'")
            return SearchResult(
                success = false,
                query = cleanQuery,
                items = emptyList(),
                summary = "No search results found for \"$cleanQuery\".",
                errorMessage = "No live web results returned."
            )
        }

        val topItems = results.distinctBy { it.url.ifBlank { it.title } }.take(4)
        val summary = buildString {
            append("Found ${topItems.size} live web sources for \"$cleanQuery\":\n")
            topItems.forEachIndexed { idx, item ->
                append("[${idx + 1}] ${item.title}: ${item.snippet} (Source: ${item.url})\n")
            }
        }

        Log.d(TAG, "[WEB_SEARCH_SUCCESS] Retrieved ${topItems.size} results for '$cleanQuery'")
        return SearchResult(
            success = true,
            query = cleanQuery,
            items = topItems,
            summary = summary
        )
    }

    private fun searchWikipedia(query: String): List<SearchItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=&srlimit=3"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val queryObj = root.optJSONObject("query") ?: return emptyList()
            val searchArr = queryObj.optJSONArray("search") ?: return emptyList()

            val items = mutableListOf<SearchItem>()
            for (i in 0 until searchArr.length()) {
                val item = searchArr.getJSONObject(i)
                val title = item.optString("title", "")
                val rawSnippet = item.optString("snippet", "")
                val cleanSnippet = rawSnippet.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").replace("&#039;", "'").trim()
                val pageId = item.optLong("pageid", 0L)
                val pageUrl = if (pageId > 0) "https://en.wikipedia.org/?curid=$pageId" else "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"

                if (title.isNotBlank()) {
                    items.add(
                        SearchItem(
                            title = title,
                            snippet = cleanSnippet,
                            url = pageUrl,
                            source = "Wikipedia"
                        )
                    )
                }
            }
            return items
        }
    }

    private fun searchDuckDuckGo(query: String): List<SearchItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)

            val items = mutableListOf<SearchItem>()
            val abstractText = root.optString("AbstractText", "")
            val abstractSource = root.optString("AbstractSource", "DuckDuckGo")
            val abstractUrl = root.optString("AbstractURL", "")
            val heading = root.optString("Heading", "")

            if (abstractText.isNotBlank()) {
                items.add(
                    SearchItem(
                        title = heading.ifBlank { query },
                        snippet = abstractText,
                        url = abstractUrl,
                        source = abstractSource
                    )
                )
            }

            val relatedTopics = root.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), 2)) {
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotBlank()) {
                        items.add(
                            SearchItem(
                                title = text.take(60),
                                snippet = text,
                                url = firstUrl,
                                source = "DuckDuckGo"
                            )
                        )
                    }
                }
            }

            return items
        }
    }
}

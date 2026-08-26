package com.joymusic.music.discord

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DiscordLoginActivity : Activity() {

    private lateinit var webView: WebView
    private var isFinished = false
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private var deferred: CompletableDeferred<String>? = null

        fun startLogin(): CompletableDeferred<String> {
            val d = CompletableDeferred<String>()
            deferred = d
            return d
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            
            // Standard mobile UA often works better for token extraction in some regions
            // but we'll use a stable one
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
            
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onRetrieveToken(token: String) {
                    val clean = token.trim().replace("\"", "")
                    if (clean.length > 50) {
                        scope.launch { onTokenFound(clean) }
                    }
                }
            }, "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.contains("discord.com") == true && !url.contains("/login")) {
                        injectTokenExtractor()
                    }
                }
            }
        }

        val container = FrameLayout(this)
        container.addView(webView)
        setContentView(container)

        webView.loadUrl("https://discord.com/login")
    }

    private fun injectTokenExtractor() {
        val script = """
            (function() {
                function send(t) {
                    if (t && t.length > 50) {
                        Android.onRetrieveToken(t);
                        return true;
                    }
                    return false;
                }

                function tryWebpack() {
                    try {
                        window.webpackChunkdiscord_app.push([
                            [Math.random()], {}, (req) => {
                                for (const m of Object.values(req.c)) {
                                    if (m.exports && m.exports.default && m.exports.default.getToken !== undefined) {
                                        send(m.exports.default.getToken());
                                    }
                                }
                            }
                        ]);
                    } catch (e) {}
                }

                function tryStorage() {
                    try {
                        const token = window.localStorage.getItem("token") || window.localStorage.getItem("__AUTH_TOKEN__");
                        if (token) send(token.replace(/"/g, ""));
                    } catch (e) {}
                }

                setInterval(() => {
                    tryStorage();
                    tryWebpack();
                }, 1000);
                
                tryStorage();
                tryWebpack();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun onTokenFound(token: String) {
        if (isFinished) return
        isFinished = true
        Timber.tag("DiscordLogin").i("Token captured successfully")
        deferred?.complete(token)
        deferred = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isFinished) {
            deferred?.cancel()
            deferred = null
        }
    }
}

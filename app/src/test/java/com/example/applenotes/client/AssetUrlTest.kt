package com.example.applenotes.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetUrlTest {
    @Test
    fun acceptsAppleAssetHostsOverHttps() {
        listOf(
            "https://cvws.icloud-content.com/B/AbCdEf/\${f}?o=abc&v=1",
            "https://p123-ckdatabasews.icloud.com/database/1/x",
            "https://cvws.icloud-content.com.cn/B/x",
            "https://gateway.icloud.com.cn/x",
            "https://www.apple.com/x",
            "https://icloud-content.com/x",
            "https://CVWS.ICLOUD-CONTENT.COM/B/x",
            "https://cvws.icloud-content.com:443/B/x",
        ).forEach { assertTrue(it, isTrustedAssetUrl(it)) }
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "http://cvws.icloud-content.com/B/x", // not https
            "https://example.com/x",
            "https://noticloud.com/x", // suffix without a label boundary
            "https://icloud.com.example.net/x",
            "https://icloud-content.com.evil.example/x",
            "https://user@cvws.icloud-content.com/x", // userinfo
            "https://cvws.icloud-content.com:8443/x",
            "file:///sdcard/x",
            "ftp://cvws.icloud-content.com/x",
            "not a url",
            "",
            "https:///nohost",
        ).forEach { assertFalse(it, isTrustedAssetUrl(it)) }
    }
}

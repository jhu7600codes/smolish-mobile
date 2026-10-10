package com.smolish

import org.junit.Assert.assertEquals
import org.junit.Test

/** A push encrypted by an independent RFC 8291 implementation (python cryptography), decrypted by the app. */
class WebPushTest {
    @Test
    fun decryptsAes128gcm() {
        val body = java.util.Base64.getDecoder().decode("SwnoRBUikMmuQrRHJTWENwAAEABBBAcmtEq/Fr2M7Uzs3dXYS/JfAyI7kWUsKS0DqNUGDwl9luJZgPYXdfFZfTOBM7nZhbv3nrZDO0Ol/7TSB8FPD6UxOfHk/4xSq1VPzNd27yyMB9ed4GEhmyc/PjTRQkbtfeNvU6Aoq6xs8YnjXALUTEOM3VsPu8SB4lOaRLr+nnxTF+TVUfMZvot/Wz448iUt5eIqxqTNApeeZTsERzOIHSvPZW7NkznA3CNiJupcc9HY1WUtauuUcLkiXezIJ2++QJ1Pr3bpRtdX9YELmnMQ11zN39zNUi/HHDX5Tw==")
        val text = WebPush.decrypt(body, "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg76acln5L5KlIkKpCF3tZTBoXa3ziX9h9PogYI1SApcGhRANCAATDHEdxww4qDKtUMFHVxr1z21TpGcZttsr832sAHpgzjqf0p1F+GBaGTGJ2QX5VD9lVZfjnDRlIUHCs4yndKKn7", "BMMcR3HDDioMq1QwUdXGvXPbVOkZxm22yvzfawAemDOOp_SnUX4YFoZMYnZBflUP2VVl-OcNGUhQcKzjKd0oqfs", "pkoDJQyDSN98SE3qgPCj4Q")
        assertEquals("""{"title": "jhu liked your smol", "body": "\u043f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440", "url": "/v/123", "tag": "like-1"}""", text)
    }
}
